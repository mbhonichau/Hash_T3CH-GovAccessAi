package za.gov.govaccess.assessment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.provider.AiProviderException;
import za.gov.govaccess.ai.repository.PromptRepository;
import za.gov.govaccess.ai.service.AiOutputInvalidException;
import za.gov.govaccess.ai.service.AiReplayMissException;
import za.gov.govaccess.ai.service.AiPromptBuilder;
import za.gov.govaccess.ai.service.StructuredOutputService;
import za.gov.govaccess.application.service.ApplicationIntakeService;
import za.gov.govaccess.assessment.dto.AssessmentExplanation;
import za.gov.govaccess.assessment.dto.AssessmentExplanation.RuleExplanation;
import za.gov.govaccess.assessment.dto.AssessmentExplanationInput;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.audit.service.CanonicalJson;
import za.gov.govaccess.security.ActorIdentity;

@Service
public class AssessmentExplanationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ApplicationIntakeService intake;
    private final StructuredOutputService model;
    private final PromptRepository prompts;
    private final BusinessAuditService audit;
    private final TransactionTemplate transactions;
    private final JsonNode schema;

    public AssessmentExplanationService(JdbcTemplate jdbc, ObjectMapper mapper, ApplicationIntakeService intake,
                                        StructuredOutputService model, PromptRepository prompts, BusinessAuditService audit,
                                        PlatformTransactionManager transactions) throws JsonProcessingException {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.intake = intake;
        this.model = model;
        this.prompts = prompts;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactions);
        this.schema = mapper.readTree("""
                {"type":"object","additionalProperties":false,"required":["summary","ruleExplanations","caseworkerFocusPoints"],
                 "properties":{"summary":{"type":"string"},"caseworkerFocusPoints":{"type":"array","items":{"type":"string"}},
                 "ruleExplanations":{"type":"array","items":{"type":"object","additionalProperties":false,
                 "required":["ruleCode","statement","evidenceUsed","nextStep"],"properties":{"ruleCode":{"type":"string"},
                 "statement":{"type":"string"},"evidenceUsed":{"type":"array","items":{"type":"string"}},"nextStep":{"type":"string"}}}}}}
                """);
    }

    public record SavedExplanation(String generatedBy, AssessmentExplanation explanation) {}
    private record Context(UUID applicationId, UUID policyId, String policyHash, String json) {}

    public Optional<SavedExplanation> saved(Authentication actor, UUID applicationId, UUID assessmentId) {
        if (!CaseAssessmentService.canReadHistory(actor) || assessmentId == null) return Optional.empty();
        intake.requireReadable(actor, applicationId, false);
        return jdbc.query("""
                SELECT c.generated_by,c.explanation FROM govaccess.assessment_explanation_cache c
                JOIN govaccess.assessment a ON a.id=c.assessment_id
                WHERE a.application_id=? AND a.id=? AND c.prompt_version=?
                """, (row, index) -> new SavedExplanation(row.getString(1), read(row.getString(2), AssessmentExplanation.class)),
                applicationId, assessmentId, prompts.active("explanation").version()).stream().findFirst();
    }

    public SavedExplanation generate(Authentication actor, UUID assessmentId) {
        ActorIdentity.require(actor, "CASE_REVIEW");
        var context = input(assessmentId);
        intake.requireReadable(actor, context.applicationId(), false);
        ensureCurrent(assessmentId);
        var prompt = prompts.active("explanation");
        String hash = CanonicalJson.sha256(context.json());
        var cached = cached(assessmentId, hash, prompt.version());
        if (cached.isPresent()) return cached.get();

        var input = read(context.json(), AssessmentExplanationInput.class);
        var attribution = new AiCallContext(ActorIdentity.of(actor).id(), "ASSESSMENT", assessmentId, context.applicationId());
        var data = read(context.json(), JsonNode.class);
        AiPromptBuilder.allowOnly(data, java.util.Set.of("policyVersion", "recommendation", "rules"));
        data.path("rules").forEach(rule -> AiPromptBuilder.allowOnly(rule,
                java.util.Set.of("ruleCode", "status", "expectedValue", "actualValue", "evidenceUsed", "requirementOperator", "sources")));
        var request = AiPromptBuilder.build(prompt, AiPromptBuilder.Data.ASSESSMENT, "", context.json(), List.of(), schema,
                false, attribution);
        SavedExplanation output;
        try {
            output = new SavedExplanation("AI", model.generateWithoutProviderCache(request, AssessmentExplanation.class,
                    explanation -> ExplanationFaithfulness.verify(input, context.json(), explanation)));
        } catch (AiOutputInvalidException | AiProviderException | AiReplayMissException e) {
            if (e instanceof za.gov.govaccess.ai.provider.AiRateLimitException limited) throw limited;
            output = new SavedExplanation("TEMPLATE", template(input));
        }
        SavedExplanation generated = output;
        return transactions.execute(transaction -> {
            intake.requireReadable(actor, context.applicationId(), true);
            ensureCurrent(assessmentId);
            var existing = cached(assessmentId, hash, prompt.version());
            if (existing.isPresent()) return existing.get();
            jdbc.update("DELETE FROM govaccess.assessment_explanation_cache WHERE assessment_id=?", assessmentId);
            jdbc.update("""
                    INSERT INTO govaccess.assessment_explanation_cache (assessment_id,input_hash,generated_by,explanation,prompt_version)
                    VALUES (?,?,?,?,?)
                    """, assessmentId, hash, generated.generatedBy(), CanonicalJson.write(generated.explanation()), prompt.version());
            var identity = ActorIdentity.of(actor);
            audit.append(new Change(context.applicationId(), identity.id(), identity.role(), "ASSESSMENT_EXPLANATION_GENERATED",
                    context.policyId(), context.policyHash(), assessmentId, null, Map.of("generatedBy", generated.generatedBy()),
                    prompt.id(), prompt.version(), null));
            return generated;
        });
    }

    private Context input(UUID id) {
        return jdbc.query("""
                SELECT a.application_id,a.policy_version_id,a.policy_hash,q.explanation_input
                FROM govaccess.assessment a JOIN govaccess.assessment_evidence_quality q ON a.id=q.assessment_id
                WHERE a.id=? AND q.explanation_input IS NOT NULL
                """, (row, index) -> new Context(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getString(3), row.getString(4)), id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Assessment explanation input is unavailable"));
    }

    private void ensureCurrent(UUID id) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM govaccess.assessment_supersession WHERE previous_assessment_id=?)
                """, Boolean.class, id))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This assessment has been superseded");
        }
    }

    public static String taggedAssessment(String json) {
        return AiPromptBuilder.tagged(AiPromptBuilder.Data.ASSESSMENT, json, false);
    }

    private Optional<SavedExplanation> cached(UUID id, String hash, int promptVersion) {
        return jdbc.query("""
                SELECT generated_by,explanation FROM govaccess.assessment_explanation_cache
                WHERE assessment_id=? AND input_hash=? AND prompt_version=?
                """, (row, index) -> new SavedExplanation(row.getString(1), read(row.getString(2), AssessmentExplanation.class)),
                id, hash, promptVersion).stream().findFirst();
    }

    public static AssessmentExplanation template(AssessmentExplanationInput input) {
        var rules = input.rules().stream().map(rule -> {
            String requirement = rule.requirementOperator() == null ? "" : " The requirement uses "
                    + rule.requirementOperator().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                    + (rule.expectedValue() == null ? "." : " " + display(rule.expectedValue()) + ".");
            String sourceDetails = rule.sources().isEmpty()
                    ? " Source document and page details were unavailable."
                    : " " + rule.sources().stream().map(source -> source.citation()
                        + (source.value() == null || source.value().isNull() ? "" : " recorded " + display(source.value())))
                        .collect(java.util.stream.Collectors.joining("; ")) + ".";
            String value = rule.actualValue() == null || rule.actualValue().isNull() ? " No single evidence value was recorded."
                    : " The recorded value was " + display(rule.actualValue()) + ".";
            String reason = switch (rule.status()) {
                case PASS -> "The verified evidence satisfies this rule.";
                case FAIL -> "The verified evidence does not satisfy this rule.";
                case UNKNOWN -> "The recorded evidence is insufficient to evaluate this rule.";
                case CONFLICT -> "Recorded evidence contains conflicting values for this rule.";
                case NOT_APPLICABLE -> "The rule does not apply to the recorded facts.";
            };
            String next = switch (rule.status()) {
                case PASS, NOT_APPLICABLE -> "Review the recorded result before deciding.";
                case FAIL -> "Check the cited evidence and correct any verified error.";
                case UNKNOWN -> "Ask for the evidence needed to evaluate this rule.";
                case CONFLICT -> "Compare the source records and verify the correct value.";
            };
            return new RuleExplanation(rule.ruleCode(), rule.status().name() + ": Rule " + rule.ruleCode()
                    + " under policy version \"" + input.policyVersion() + "\". " + reason + requirement + value + sourceDetails,
                    rule.evidenceUsed(), next);
        }).toList();
        var focus = java.util.stream.Stream.concat(
                input.rules().stream().filter(rule -> switch (rule.status()) {
                    case FAIL, UNKNOWN, CONFLICT -> true;
                    default -> false;
                }).limit(2).map(rule -> "Check the evidence needed to resolve rule " + rule.ruleCode() + "."),
                java.util.stream.Stream.of("Check the recorded requirements against the cited evidence.",
                        "Confirm the recorded policy version before deciding.")).limit(3).toList();
        return new AssessmentExplanation("Recommendation: " + input.recommendation()
                + ". Policy version: \"" + input.policyVersion() + "\". An authorised official must make the decision.",
                rules, focus);
    }

    private static String display(JsonNode value) {
        return value.isNumber() ? value.decimalValue().stripTrailingZeros().toPlainString() : value.asText(value.toString());
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readerFor(type).with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored explanation data", e);
        }
    }
}
