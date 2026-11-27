package za.gov.govaccess.communication.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
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
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.audit.service.CanonicalJson;
import za.gov.govaccess.communication.dto.EvidenceRequestDraft;
import za.gov.govaccess.communication.dto.EvidenceRequestDraft.RequestedItem;
import za.gov.govaccess.policy.service.RulePolicyService;
import za.gov.govaccess.rules.dto.RuleEvaluation.Status;
import za.gov.govaccess.rules.model.RuleDocument.Rule;
import za.gov.govaccess.security.ActorIdentity;

@Service
public class CaseCommunicationService {
    public static final String EVIDENCE = "EVIDENCE_REQUEST";
    public static final String NOTICE = "DECISION_NOTICE";
    private static final List<String> GREETINGS = List.of("Please help us check the evidence for your application.",
            "We need your help to confirm the information in your application.");
    private static final List<String> SUMMARIES = List.of("Check the listed evidence before continuing.",
            "Review the listed differences and missing evidence.");
    private static final List<String> OPENINGS = List.of("This notice explains the official decision on your application.",
            "Read the official decision and the reasons below.");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ApplicationIntakeService intake;
    private final RulePolicyService policies;
    private final PromptRepository prompts;
    private final StructuredOutputService ai;
    private final BusinessAuditService audit;
    private final TransactionTemplate transactions;
    private final double readingTarget;
    private final String language;
    private final Map<String, String> glossary;

    public CaseCommunicationService(JdbcTemplate jdbc, ObjectMapper mapper, ApplicationIntakeService intake,
            RulePolicyService policies, PromptRepository prompts, StructuredOutputService ai, BusinessAuditService audit,
            PlatformTransactionManager transactions, @Value("${govaccess.communication.reading-grade-target:8}") double readingTarget,
            @Value("${govaccess.communication.translation-language:}") String language,
            @Value("${govaccess.communication.translation-glossary:}") String glossaryText) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.intake = intake;
        this.policies = policies;
        this.prompts = prompts;
        this.ai = ai;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactions);
        if (!Double.isFinite(readingTarget) || readingTarget < 1 || readingTarget > 20
                || !language.isBlank() && !language.strip().matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}")) {
            throw new IllegalArgumentException("Invalid communication settings");
        }
        this.readingTarget = readingTarget;
        this.language = language.strip();
        var terms = new LinkedHashMap<String, String>();
        for (String entry : glossaryText.split("\\|")) {
            if (entry.isBlank()) continue;
            var pair = entry.split("=", 2);
            if (pair.length != 2 || pair[0].isBlank() || pair[1].isBlank()) {
                throw new IllegalArgumentException("Set the translation glossary as English=translation pairs separated by |");
            }
            terms.put(pair[0].strip(), pair[1].strip());
        }
        glossary = Map.copyOf(terms);
    }

    public record Message(UUID id, UUID applicationId, UUID assessmentId, UUID decisionId, String kind, String state,
                          String english, String officerSummary, String generatedBy, String translation, String language,
                          boolean translationApproved) {
        public double readingGrade() { return CommunicationGuard.grade(english); }
    }
    public record Procedure(String text, UUID approvedBy, OffsetDateTime approvedAt) {}
    private record Context(UUID application, UUID assessment, UUID decision, UUID programme, UUID policy, String hash,
                           String version, String policyContent, String input, String findings, String snapshot,
                           String outcome, String rationale) {}
    public record Opening(@NotBlank String opening) {}
    public record Translation(@NotBlank @Size(max = 12000) String text) {}
    private record GeneratedText(String body, String summary, List<RequestedItem> items, String method) {}
    private record RecoveryItem(String ruleCode, List<String> documents, List<BigDecimal> observedAmounts, boolean conflict) {}

    public double readingTarget() { return readingTarget; }
    public String translationLanguage() { return language; }

    public boolean hasProcedure(Authentication actor, UUID application) {
        var caseRecord = intake.requireReadable(actor, application, false);
        return procedure(caseRecord.getProgramme().getId()).isPresent();
    }

    public List<Message> messages(Authentication actor, UUID application) {
        intake.requireReadable(actor, application, false);
        boolean staff = canReview(actor);
        var messages = jdbc.query(messageSql() + " WHERE application_id=? AND invalidated=false AND (? OR state='SENT') ORDER BY created_at DESC",
                this::message, application, staff);
        return staff ? messages : messages.stream().map(CaseCommunicationService::applicantView).toList();
    }

    public Message view(Authentication actor, UUID application, UUID id) {
        intake.requireReadable(actor, application, false);
        var message = find(application, id);
        if (canReview(actor)) return message;
        if (!"SENT".equals(message.state())) throw missing();
        return applicantView(message);
    }

    public Optional<Procedure> procedure(UUID programme) {
        return jdbc.query("""
                SELECT procedure_text,approved_by,approved_at FROM govaccess.programme_notice_configuration WHERE programme_id=?
                """, (row, index) -> new Procedure(row.getString(1), row.getObject(2, UUID.class),
                row.getObject(3, OffsetDateTime.class)), programme).stream().findFirst();
    }

    @Transactional
    public void approveProcedure(Authentication actor, UUID programme, String text) {
        ActorIdentity.require(actor, "POLICY_PUBLISH");
        if (text == null || text.isBlank() || text.length() > 4000) throw new IllegalArgumentException("Enter approved review or appeal wording.");
        jdbc.queryForObject("SELECT id FROM govaccess.programme WHERE id=? FOR UPDATE", UUID.class, programme);
        var previous = procedure(programme).orElse(null);
        var current = new Procedure(text.strip(), ActorIdentity.of(actor).id(), OffsetDateTime.now());
        if (jdbc.update("""
                UPDATE govaccess.programme_notice_configuration SET procedure_text=?,approved_by=?,approved_at=? WHERE programme_id=?
                """, current.text(), current.approvedBy(), current.approvedAt(), programme) == 0) {
            jdbc.update("INSERT INTO govaccess.programme_notice_configuration VALUES (?,?,?,?)",
                    programme, current.text(), current.approvedBy(), current.approvedAt());
        }
        var identity = ActorIdentity.of(actor);
        audit.append(new Change(null, identity.id(), identity.role(), "PROGRAMME_NOTICE_PROCEDURE_APPROVED", null, null, null,
                previous == null ? null : Map.of("programmeId", programme, "text", previous.text()),
                Map.of("programmeId", programme, "text", current.text()), null, null, null));
    }

    public Message draft(Authentication actor, UUID application, String kind) {
        requireFor(actor, kind);
        intake.requireReadable(actor, application, false);
        Context context = context(application, kind);
        var existing = existing(context, kind);
        if (existing.isPresent()) return existing.get();
        GeneratedText generated = kind.equals(EVIDENCE) ? evidenceDraft(actor, context) : noticeDraft(actor, context);
        return transactions.execute(status -> {
            intake.requireReadable(actor, application, true);
            if (!context.equals(context(application, kind))) throw new IllegalArgumentException("The case changed. Refresh and try again.");
            var cached = existing(context, kind);
            if (cached.isPresent()) return cached.get();
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO govaccess.case_communication (id,application_id,assessment_id,decision_id,kind,state,
                    english_text,request_items,officer_summary,generated_by,created_at)
                    VALUES (?,?,?,?,?,'DRAFT',?,?,?,?,?)
                    """, id, application, context.assessment(), context.decision(), kind, generated.body(),
                    CanonicalJson.write(generated.items()), generated.summary(), generated.method(), OffsetDateTime.now());
            event(actor, context, "CASE_COMMUNICATION_DRAFTED", null,
                    Map.of("communicationId", id, "kind", kind, "generatedBy", generated.method()),
                    kind.equals(EVIDENCE) ? "evidence-recovery" : "decision-notice");
            return find(application, id);
        });
    }

    private GeneratedText evidenceDraft(Authentication actor, Context context) {
        var input = read(context.input(), za.gov.govaccess.assessment.dto.AssessmentExplanationInput.class);
        var frozen = read(context.snapshot(), JsonNode.class);
        var findings = read(context.findings(), JsonNode.class);
        var byCode = policies.read(context.policyContent()).rules().stream().flatMap(CaseCommunicationService::flatten)
                .collect(java.util.stream.Collectors.toMap(Rule::ruleCode, rule -> rule));
        var names = jdbc.query("SELECT type_key,label FROM govaccess.document_type", rows -> {
            var result = new LinkedHashMap<String, String>();
            while (rows.next()) result.put(rows.getString(1), rows.getString(2));
            return result;
        });
        var fields = new ArrayList<JsonNode>();
        frozen.path("fields").forEach(fields::add);
        var recovery = new ArrayList<RecoveryItem>();
        for (var result : input.rules()) {
            Rule rule = byCode.get(result.ruleCode());
            if (rule == null) continue;
            boolean finding = rule.fieldKey() != null && java.util.stream.StreamSupport.stream(findings.spliterator(), false)
                    .anyMatch(item -> rule.fieldKey().equals(item.path("field").asText()));
            if (result.status() != Status.UNKNOWN && result.status() != Status.CONFLICT && !finding) continue;
            var required = new LinkedHashSet<>(rule.requiredEvidence());
            if (rule.operator() == za.gov.govaccess.rules.enums.RuleOperator.DOCUMENT_REQUIRED && rule.expectedValue() != null) {
                required.add(rule.expectedValue().asText());
            }
            var missing = required.stream().filter(type -> fields.stream().noneMatch(field ->
                    type.equals(field.path("type").asText()) && "SUCCEEDED".equals(field.path("state").asText())
                    && field.path("field").path("verified").asBoolean()
                    && !"FAILED".equals(field.path("field").path("validation").asText()))).toList();
            var available = result.status() == Status.CONFLICT || finding ? required.stream().toList() : missing;
            var amounts = new LinkedHashSet<BigDecimal>();
            if (rule.unit() != null && rule.unit().toUpperCase(Locale.ROOT).startsWith("ZAR")) {
                result.sources().stream().map(za.gov.govaccess.assessment.dto.AssessmentExplanationInput.Source::value)
                        .filter(value -> value != null && value.isNumber()).map(JsonNode::decimalValue)
                        .map(BigDecimal::stripTrailingZeros).forEach(amounts::add);
                fields.stream().filter(field -> {
                    var saved = field.path("field");
                    return result.evidenceUsed().contains(saved.path("documentId").asText() + ":"
                            + saved.path("documentVersion").asInt() + ":" + field.path("key").asText());
                }).map(field -> field.path("field").path("value")).filter(JsonNode::isNumber).map(JsonNode::decimalValue)
                        .map(BigDecimal::stripTrailingZeros).forEach(amounts::add);
                if (finding) {
                    var declared = frozen.path("declaredValues").path(rule.fieldKey());
                    if (declared.isNumber()) amounts.add(declared.decimalValue().stripTrailingZeros());
                }
            }
            recovery.add(new RecoveryItem(rule.ruleCode(), available.stream().map(type -> names.getOrDefault(type, type)).toList(),
                    List.copyOf(amounts), result.status() == Status.CONFLICT || finding));
        }
        if (recovery.isEmpty()) throw new IllegalArgumentException("This assessment has no unresolved evidence findings to request.");
        List<RequestedItem> requested = recovery.stream().map(item -> new RequestedItem(item.ruleCode(), item.documents())).toList();
        var contract = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        contract.putArray("required").add("officerSummary").add("applicantMessage").add("requestedItems");
        var properties = contract.putObject("properties");
        properties.putObject("officerSummary").put("type", "string");
        properties.putObject("applicantMessage").put("type", "string");
        var item = properties.putObject("requestedItems").put("type", "array").putObject("items").put("type", "object")
                .put("additionalProperties", false);
        item.putArray("required").add("ruleCode").add("documentNames");
        var details = item.putObject("properties");
        details.putObject("ruleCode").put("type", "string");
        details.putObject("documentNames").put("type", "array").putObject("items").put("type", "string");
        String json = CanonicalJson.write(Map.of("allowedOfficerSummaries", SUMMARIES, "allowedApplicantGreetings", GREETINGS, "requestedItems", requested,
                "evidenceFindings", recovery));
        EvidenceRequestDraft output;
        String method;
        try {
            output = ai.generateWithoutProviderCache(request(actor, context, "evidence-recovery", json, contract),
                    EvidenceRequestDraft.class, draft -> {
                        CommunicationGuard.onlyRecordedValues(json, draft.officerSummary() + " " + draft.applicantMessage());
                        if (!SUMMARIES.contains(draft.officerSummary()) || !GREETINGS.contains(draft.applicantMessage())
                                || !requested.equals(draft.requestedItems())) {
                            CommunicationGuard.invalid("Use the supplied wording, rule codes and document names exactly");
                        }
                    });
            method = "AI";
        } catch (AiOutputInvalidException | AiProviderException | AiReplayMissException e) {
            if (e instanceof za.gov.govaccess.ai.provider.AiRateLimitException limited) throw limited;
            output = new EvidenceRequestDraft(SUMMARIES.getFirst(), GREETINGS.getFirst(), requested);
            method = "TEMPLATE";
        }
        return new GeneratedText(output.applicantMessage() + "\n\n" + recovery.stream().map(CaseCommunicationService::evidenceLine)
                .collect(java.util.stream.Collectors.joining("\n\n")), output.officerSummary(), output.requestedItems(), method);
    }

    private GeneratedText noticeDraft(Authentication actor, Context context) {
        var input = read(context.input(), za.gov.govaccess.assessment.dto.AssessmentExplanationInput.class);
        var facts = input.rules().stream().map(rule -> "[" + rule.ruleCode() + "] "
                + (rule.requirementOperator() == null ? "Recorded criterion"
                        : rule.requirementOperator().toLowerCase(Locale.ROOT).replace('_', ' '))
                + (rule.expectedValue() == null || rule.expectedValue().isNull() ? "" : " " + display(rule.expectedValue()))
                + (rule.actualValue() == null || rule.actualValue().isNull() ? ". No single verified value was recorded."
                        : ". Recorded value: " + display(rule.actualValue()) + ".")).toList();
        String body = "Official decision: " + switch (context.outcome()) {
            case "APPROVED" -> "The official recorded an approval for your application.";
            case "DECLINED" -> "Your application was declined.";
            default -> "More evidence is required.";
        } + "\n\nReason recorded by the official\n" + context.rationale()
                + "\n\nPolicy version: " + context.version() + "\n\nCriteria and recorded facts\n" + String.join("\n", facts);
        String opening = OPENINGS.getFirst();
        String method = "TEMPLATE";
        try {
            String json = CanonicalJson.write(Map.of("officialOutcome", context.outcome(), "officialReason", context.rationale(),
                    "policyVersion", context.version(), "criteriaAndFacts", facts, "allowedOpenings", OPENINGS));
            opening = ai.generateWithoutProviderCache(request(actor, context, "decision-notice", json, textSchema("opening")),
                    Opening.class, result -> {
                        CommunicationGuard.neutral(result.opening());
                        if (!OPENINGS.contains(result.opening())) CommunicationGuard.invalid("Use an allowed opening");
                    }).opening();
            method = "AI";
        } catch (AiOutputInvalidException | AiProviderException | AiReplayMissException e) {
            if (e instanceof za.gov.govaccess.ai.provider.AiRateLimitException limited) throw limited;
            // The fixed English opening and recorded decision facts are the fallback.
        }
        return new GeneratedText(opening + "\n\n" + body, null, List.of(), method);
    }

    @Transactional
    public void approve(Authentication actor, UUID application, UUID id) {
        ActorIdentity.require(actor, "NOTICE_APPROVE");
        var app = intake.requireReadable(actor, application, true);
        var message = find(application, id);
        if (!NOTICE.equals(message.kind()) || !"DRAFT".equals(message.state())) throw new IllegalArgumentException("Only a draft decision notice can be approved.");
        var procedure = procedure(app.getProgramme().getId()).orElseThrow(() ->
                new IllegalArgumentException("Approval is blocked until the programme has approved appeal or review instructions."));
        String english = message.english() + "\n\nReview or appeal\n" + procedure.text();
        requireNeutralForPublication(english);
        jdbc.update("UPDATE govaccess.case_communication SET english_text=?,state='APPROVED',approved_at=? WHERE id=?",
                english, OffsetDateTime.now(), id);
        event(actor, context(application, NOTICE), "DECISION_NOTICE_APPROVED", Map.of("state", "DRAFT"),
                Map.of("communicationId", id, "state", "APPROVED", "procedureApprovedBy", procedure.approvedBy(),
                        "procedureApprovedAt", procedure.approvedAt().toString(), "englishHash", CanonicalJson.sha256(english)), null);
    }

    @Transactional
    public void send(Authentication actor, UUID application, UUID id, String rationale) {
        var message = view(actor, application, id);
        requireFor(actor, message.kind());
        intake.requireReadable(actor, application, true);
        message = find(application, id);
        boolean evidence = EVIDENCE.equals(message.kind());
        String required = evidence ? "DRAFT" : "APPROVED";
        if (!required.equals(message.state())) throw new IllegalArgumentException(evidence
                ? "Only an unsent evidence draft can be sent." : "Approve the notice before sending it.");
        if (rationale == null || rationale.isBlank() || rationale.length() > 1000) throw new IllegalArgumentException("Enter a reason for sending.");
        var context = context(application, message.kind());
        if (!context.assessment().equals(message.assessmentId())) throw new IllegalArgumentException("Create a draft for the current assessment.");
        requireNeutralForPublication(message.english());
        jdbc.update("UPDATE govaccess.case_communication SET state='SENT',sent_at=? WHERE id=?", OffsetDateTime.now(), id);
        event(actor, context, evidence ? "REQUEST_ADDITIONAL_EVIDENCE" : "DECISION_NOTICE_SENT",
                Map.of("state", required), Map.of("communicationId", id, "state", "SENT", "reason", rationale.strip()), null);
    }

    public boolean translate(Authentication actor, UUID application, UUID id) {
        ActorIdentity.require(actor, "NOTICE_APPROVE");
        intake.requireReadable(actor, application, false);
        var message = find(application, id);
        if (language.isBlank()) throw new IllegalArgumentException("Configure a verified target language before translating.");
        if (!NOTICE.equals(message.kind()) || !"APPROVED".equals(message.state())) {
            throw new IllegalArgumentException("Translate after approving the English notice and before sending it.");
        }
        if (message.translation() != null) return true;
        var context = context(application, NOTICE);
        String text;
        try {
            String json = CanonicalJson.write(Map.of("english", message.english(), "language", language, "glossary", glossary));
            text = ai.generateWithoutProviderCache(request(actor, context, "translation", json, textSchema("text")),
                    Translation.class, result -> CommunicationGuard.translation(message.english(), result.text(), glossary)).text();
        } catch (AiOutputInvalidException | AiProviderException | AiReplayMissException e) {
            if (e instanceof za.gov.govaccess.ai.provider.AiRateLimitException limited) throw limited;
            transactions.executeWithoutResult(status -> event(actor, context, "NOTICE_TRANSLATION_FALLBACK", null,
                    Map.of("communicationId", id, "generatedBy", "TEMPLATE", "language", "English"), "translation"));
            return false;
        }
        transactions.executeWithoutResult(status -> {
            intake.requireReadable(actor, application, true);
            var latest = find(application, id);
            if (!"APPROVED".equals(latest.state())) throw new IllegalArgumentException("The English notice has already been sent.");
            if (latest.translation() != null) return;
            jdbc.update("UPDATE govaccess.case_communication SET translation_text=?,translation_language=? WHERE id=?", text, language, id);
            event(actor, context, "NOTICE_TRANSLATION_DRAFTED", null,
                    Map.of("communicationId", id, "language", language, "translationHash", CanonicalJson.sha256(text)), "translation");
        });
        return true;
    }

    @Transactional
    public void approveTranslation(Authentication actor, UUID application, UUID id) {
        ActorIdentity.require(actor, "NOTICE_APPROVE");
        intake.requireReadable(actor, application, true);
        var message = find(application, id);
        if (!"APPROVED".equals(message.state()) || message.translation() == null || message.translationApproved()) {
            throw new IllegalArgumentException("No machine translation is awaiting review before sending.");
        }
        try { CommunicationGuard.translation(message.english(), message.translation(), glossary); }
        catch (AiOutputInvalidException e) { throw new IllegalArgumentException("The translation no longer passes the configured checks."); }
        jdbc.update("UPDATE govaccess.case_communication SET translation_approved=true WHERE id=?", id);
        event(actor, context(application, NOTICE), "NOTICE_TRANSLATION_APPROVED", Map.of("approved", false),
                Map.of("communicationId", id, "approved", true, "language", message.language()), null);
    }

    private Context context(UUID application, String kind) {
        String relation = kind.equals(NOTICE)
                ? "JOIN govaccess.official_decision d ON d.application_id=a.application_id AND d.assessment_id=a.id"
                : "JOIN govaccess.decision_handoff h ON h.assessment_id=a.id AND h.type='ADDITIONAL_EVIDENCE' AND h.invalidated=false";
        String columns = kind.equals(NOTICE) ? "d.id,d.decision,d.rationale" : "NULL,NULL,NULL";
        String latest = kind.equals(NOTICE) ? "" : """
                 AND a.id=(SELECT newest.id FROM govaccess.assessment newest
                 JOIN govaccess.assessment_evidence_quality quality ON quality.assessment_id=newest.id
                 WHERE newest.application_id=a.application_id ORDER BY newest.assessed_at DESC,newest.id DESC LIMIT 1)
                """;
        return jdbc.query("""
                SELECT a.id,c.programme_id,a.policy_version_id,a.policy_hash,p.version_label,p.content,q.explanation_input,
                       q.findings,s.payload,
                """ + columns + "\n" + """
                FROM govaccess.assessment a JOIN govaccess.case_application c ON c.id=a.application_id
                JOIN govaccess.policy_version p ON p.id=a.policy_version_id
                JOIN govaccess.assessment_evidence_quality q ON q.assessment_id=a.id
                JOIN govaccess.audit_protected_snapshot s ON s.id=q.input_snapshot_id
                """ + relation + " WHERE a.application_id=? AND q.explanation_input IS NOT NULL"
                + " AND NOT EXISTS (SELECT 1 FROM govaccess.assessment_supersession z WHERE z.previous_assessment_id=a.id)"
                + latest + " ORDER BY a.assessed_at DESC,a.id DESC LIMIT 1",
                (row, index) -> new Context(application, row.getObject(1, UUID.class),
                kind.equals(NOTICE) ? row.getObject(10, UUID.class) : null, row.getObject(2, UUID.class),
                row.getObject(3, UUID.class), row.getString(4), row.getString(5), row.getString(6), row.getString(7),
                row.getString(8), row.getString(9), row.getString(11), row.getString(12)), application).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(kind.equals(NOTICE)
                        ? "Record an official decision before drafting a decision notice."
                        : "Request additional evidence in the decision workspace before drafting the message."));
    }

    private Optional<Message> existing(Context context, String kind) {
        return jdbc.query(messageSql() + " WHERE application_id=? AND assessment_id=? AND kind=? AND invalidated=false ORDER BY created_at DESC LIMIT 1",
                this::message, context.application(), context.assessment(), kind).stream().findFirst();
    }

    private Message find(UUID application, UUID id) {
        return jdbc.query(messageSql() + " WHERE id=? AND application_id=? AND invalidated=false", this::message, id, application)
                .stream().findFirst().orElseThrow(CaseCommunicationService::missing);
    }

    private static String messageSql() { return "SELECT * FROM govaccess.case_communication"; }

    private static Message applicantView(Message message) {
        return new Message(message.id(), message.applicationId(), message.assessmentId(), message.decisionId(), message.kind(),
                message.state(), message.english(), null, null, message.translationApproved() ? message.translation() : null,
                message.translationApproved() ? message.language() : null, message.translationApproved());
    }

    private Message message(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Message(row.getObject("id", UUID.class), row.getObject("application_id", UUID.class),
                row.getObject("assessment_id", UUID.class), row.getObject("decision_id", UUID.class), row.getString("kind"),
                row.getString("state"), row.getString("english_text"), row.getString("officer_summary"),
                row.getString("generated_by"), row.getString("translation_text"), row.getString("translation_language"),
                row.getBoolean("translation_approved"));
    }

    private AiRequest request(Authentication actor, Context context, String key, String content, JsonNode schema) {
        var prompt = prompts.active(key);
        AiPromptBuilder.allowOnly(read(content, JsonNode.class), switch (key) {
            case "evidence-recovery" -> java.util.Set.of("allowedOfficerSummaries", "allowedApplicantGreetings", "requestedItems", "evidenceFindings");
            case "decision-notice" -> java.util.Set.of("officialOutcome", "officialReason", "policyVersion", "criteriaAndFacts", "allowedOpenings");
            case "translation" -> java.util.Set.of("english", "language", "glossary");
            default -> throw new IllegalArgumentException("No communication prompt allowlist is configured.");
        });
        return AiPromptBuilder.build(prompt, AiPromptBuilder.Data.ASSESSMENT, "", content, List.of(), schema,
                false, new AiCallContext(ActorIdentity.of(actor).id(), "ASSESSMENT", context.assessment(), context.application()));
    }

    private JsonNode textSchema(String key) {
        var schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add(key);
        schema.putObject("properties").putObject(key).put("type", "string");
        return schema;
    }

    private void event(Authentication actor, Context context, String action, Object before, Object after, String prompt) {
        var identity = ActorIdentity.of(actor);
        audit.append(new Change(context.application(), identity.id(), identity.role(), action, context.policy(),
                context.hash(), context.assessment(), before, after, prompt,
                prompt == null ? null : prompts.active(prompt).version(), null));
    }

    private <T> T read(String json, Class<T> type) {
        try { return mapper.readerFor(type).with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(json); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored communication input", e); }
    }

    private static String evidenceLine(RecoveryItem item) {
        StringBuilder line = new StringBuilder("[").append(item.ruleCode()).append("] ");
        if (item.observedAmounts().size() > 1) {
            var format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.UK));
            format.setMaximumFractionDigits(Math.max(2, item.observedAmounts().stream().mapToInt(BigDecimal::scale).max().orElse(0)));
            line.append("We recorded different amounts: ")
                    .append(item.observedAmounts().stream().map(amount -> "R" + format.format(amount)).collect(java.util.stream.Collectors.joining(" and ")))
                    .append(". ");
        } else if (item.conflict()) {
            line.append("The recorded information differs. ");
        }
        return line.append(item.documents().isEmpty() ? "Please help us confirm the correct information."
                : "Please provide or confirm: " + String.join(", ", item.documents()) + ".").toString();
    }

    private static Stream<Rule> flatten(Rule rule) { return Stream.concat(Stream.of(rule), rule.children().stream().flatMap(CaseCommunicationService::flatten)); }
    private static String display(JsonNode value) { return value.asText(value.toString()); }
    public static boolean canReview(Authentication actor) {
        return ActorIdentity.has(actor, "CASE_REVIEW") || ActorIdentity.has(actor, "AUDIT_VERIFY");
    }
    private static void requireFor(Authentication actor, String kind) {
        if (EVIDENCE.equals(kind)) ActorIdentity.require(actor, "EVIDENCE_REQUEST");
        else if (NOTICE.equals(kind)) ActorIdentity.require(actor, "NOTICE_APPROVE");
        else throw new IllegalArgumentException("Choose an evidence request or a decision notice.");
    }
    private static void requireNeutralForPublication(String text) {
        try { CommunicationGuard.neutral(text); }
        catch (AiOutputInvalidException e) { throw new IllegalArgumentException("The English wording requires neutral language before publication."); }
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Communication not found"); }
}
