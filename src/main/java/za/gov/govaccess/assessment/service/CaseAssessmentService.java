package za.gov.govaccess.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.application.service.ApplicationIntakeService;
import za.gov.govaccess.application.enums.CaseRecommendation;
import za.gov.govaccess.assessment.dto.AssessmentExplanationInput;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.audit.service.CanonicalJson;
import za.gov.govaccess.document.dto.ExtractedField;
import za.gov.govaccess.document.dto.ExtractedField.Validation;
import za.gov.govaccess.document.service.ApplicationDocuments;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.policy.service.RulePolicyService;
import za.gov.govaccess.rules.dto.RuleEvaluation.Status;
import za.gov.govaccess.rules.dto.VerifiedRuleEvidence;
import za.gov.govaccess.rules.model.RuleDocument.Rule;
import za.gov.govaccess.rules.service.RecommendationService;
import za.gov.govaccess.rules.service.RecommendationService.Recommendation;
import za.gov.govaccess.security.ActorIdentity;

@Service
public class CaseAssessmentService {
    private final ApplicationIntakeService intake;
    private final ApplicationDocuments documents;
    private final PolicyVersionRepository policies;
    private final RulePolicyService rules;
    private final RecommendationService recommendations;
    private final BusinessAuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Scoring scoring;

    public CaseAssessmentService(ApplicationIntakeService intake, ApplicationDocuments documents, PolicyVersionRepository policies,
                                 RulePolicyService rules, RecommendationService recommendations, BusinessAuditService audit,
                                 JdbcTemplate jdbc, ObjectMapper mapper,
                                 @Value("${govaccess.assessment.completeness-weight:0.5}") double completeness,
                                 @Value("${govaccess.assessment.verification-weight:0.3}") double verification,
                                 @Value("${govaccess.assessment.extraction-weight:0.2}") double extraction,
                                 @Value("${govaccess.assessment.medium-threshold:0.6}") double medium,
                                 @Value("${govaccess.assessment.high-threshold:0.85}") double high) {
        this.intake = intake;
        this.documents = documents;
        this.policies = policies;
        this.rules = rules;
        this.recommendations = recommendations;
        this.audit = audit;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.scoring = new Scoring(completeness, verification, extraction, medium, high);
        if (!Stream.of(completeness, verification, extraction, medium, high).allMatch(n -> n >= 0 && n <= 1)
                || Math.abs(completeness + verification + extraction - 1) > 0.000001 || medium >= high) {
            throw new IllegalArgumentException("Assessment weights must total 1 and thresholds must increase within 0 to 1");
        }
    }

    public record Scoring(double completenessWeight, double verificationWeight, double extractionWeight,
                          double mediumThreshold, double highThreshold) {}
    public record Factor(String name, double value, double weight) {}
    public record Quality(double satisfaction, double completeness, double confidence, String label, List<Factor> factors) {}
    public record Finding(String code, String field, List<String> evidenceReferences) {}
    public record Report(UUID assessmentId, UUID snapshotId, Quality quality, List<Finding> findings,
                         OffsetDateTime assessedAt, String recommendation, Scoring configuration) {
        public String recommendationLabel() { return CaseRecommendation.valueOf(recommendation).displayLabel(); }
    }
    private record FieldInput(String type, String state, String key, ExtractedField field) {
        public String reference() { return field.documentId() + ":" + field.documentVersion() + ":" + key; }
        public boolean verified() {
            return "SUCCEEDED".equals(state) && field.verified() && field.validation() != Validation.FAILED;
        }
    }

    @Transactional
    public UUID run(Authentication actor, UUID applicationId) {
        return assess(actor, applicationId);
    }

    @Transactional
    public UUID assess(Authentication actor, UUID applicationId) {
        ActorIdentity.require(actor, "ASSESSMENT_RUN");
        var application = intake.requireReadable(actor, applicationId, true);
        application.requireOpen();
        var policy = Optional.ofNullable(application.getPolicyVersionId()).flatMap(policies::findById)
                .filter(item -> "PUBLISHED".equals(item.getStatus()))
                .orElseThrow(() -> new IllegalArgumentException("This application requires a published policy snapshot."));
        if (!CanonicalJson.sha256(CanonicalJson.document(policy.getContent())).equals(policy.getContentHash())) {
            throw new IllegalStateException("Published policy hash failed verification");
        }
        var document = rules.read(policy.getContent());
        JsonNode declared = read(application.getDeclaredValues() == null ? "{}" : application.getDeclaredValues(), JsonNode.class);
        var sources = documents.list(actor, applicationId).stream().filter(item -> item.type() != null).toList();
        var inputs = sources.stream().flatMap(doc -> doc.fields().entrySet().stream()
                .map(entry -> new FieldInput(doc.type(), doc.state(), entry.getKey(), entry.getValue())))
                .sorted(Comparator.comparing(FieldInput::reference)).toList();
        var findings = reconcile(declared, inputs);
        var evidence = inputs.stream().filter(input -> input.verified() && input.field().value() != null)
                .map(input -> new VerifiedRuleEvidence(input.reference(), input.type(), input.key(), input.field().value())).toList();
        var evaluation = rules.evaluate(document, evidence);
        var applicable = evaluation.evaluations().stream().filter(rule -> rule.status() != Status.NOT_APPLICABLE).toList();
        var rootCodes = document.rules().stream().map(Rule::ruleCode).collect(Collectors.toSet());
        var roots = applicable.stream().filter(rule -> rootCodes.contains(rule.ruleCode())).toList();
        double satisfaction = ratio(roots.stream().filter(result -> result.status() == Status.PASS).count(), roots.size());
        double completeness = ratio(roots.stream().filter(result -> result.status() == Status.PASS
                || result.status() == Status.FAIL || result.status() == Status.CONFLICT).count(), roots.size());
        var provided = inputs.stream().filter(input -> input.field().value() != null).toList();
        double verification = ratio(provided.stream().filter(FieldInput::verified).count(), provided.size());
        double extraction = provided.stream().filter(input -> "SUCCEEDED".equals(input.state())
                        && input.field().validation() != Validation.FAILED)
                .mapToDouble(input -> input.field().humanCorrected() && input.verified() ? 1 : input.field().confidence())
                .average().orElse(0);
        double confidence = Math.min(1, completeness * scoring.completenessWeight()
                + verification * scoring.verificationWeight() + extraction * scoring.extractionWeight());
        String label = confidence >= scoring.highThreshold() ? "High" : confidence >= scoring.mediumThreshold() ? "Medium" : "Low";
        var quality = new Quality(satisfaction, completeness, confidence, label, List.of(
                new Factor("Evidence completeness", completeness, scoring.completenessWeight()),
                new Factor("Field verification", verification, scoring.verificationWeight()),
                new Factor("Extraction confidence or verified correction", extraction, scoring.extractionWeight())));
        Recommendation recommendation = recommendations.recommend(document, evaluation, !findings.isEmpty() || label.equals("Low"));
        application.recordAssessment(CaseRecommendation.valueOf(recommendation.name()));
        UUID assessment = UUID.randomUUID();
        Map<String, Object> snapshot = Map.of("schemaVersion", 1, "applicationId", applicationId, "policyId", policy.getId(),
                "policyHash", policy.getContentHash(), "declaredValues", declared, "fields", inputs, "ruleEvaluations", evaluation.evaluations());
        UUID snapshotId = audit.storeProtectedSnapshot(snapshot);
        jdbc.update("""
                INSERT INTO govaccess.assessment (id,application_id,policy_version_id,recommendation,confidence,summary,
                    assessed_by,assessed_at,policy_hash)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, assessment, applicationId, policy.getId(), recommendation.name(),
                java.math.BigDecimal.valueOf(confidence).setScale(4, java.math.RoundingMode.HALF_UP),
                !findings.isEmpty() ? "Evidence exceptions require review."
                        : label.equals("Low") ? "Evidence quality is low and requires review." : "Assessment based on verified evidence.",
                actor.getName(), OffsetDateTime.now(), policy.getContentHash());
        var evaluatedRules = document.rules().stream().flatMap(CaseAssessmentService::flatten)
                .collect(Collectors.toMap(Rule::ruleCode, rule -> rule.operator().name()));
        var sourcesByReference = inputs.stream().filter(FieldInput::verified).collect(Collectors.toMap(FieldInput::reference,
                field -> new AssessmentExplanationInput.Source(field.field().documentId().toString(),
                        field.field().pageNumber(), field.reference(), field.field().value())));
        var explanationInput = new AssessmentExplanationInput(policy.getVersionLabel(), recommendation.name(),
                evaluation.evaluations().stream().map(result -> AssessmentExplanationInput.EvaluatedRule.from(result,
                        evaluatedRules.get(result.ruleCode()), result.evidenceRefs().stream().map(sourcesByReference::get)
                                .filter(Objects::nonNull).toList())).toList());
        jdbc.update("""
                INSERT INTO govaccess.assessment_evidence_quality
                (assessment_id,input_snapshot_id,findings,breakdown,active_configuration,explanation_input) VALUES (?,?,?,?,?,?)
                """, assessment, snapshotId, CanonicalJson.write(findings), CanonicalJson.write(quality), CanonicalJson.write(scoring),
                CanonicalJson.write(explanationInput));
        var identity = ActorIdentity.of(actor);
        append(applicationId, assessment, policy.getId(), policy.getContentHash(), identity, "RECONCILIATION_RUN",
                Map.of("snapshotId", snapshotId, "exceptionCount", findings.size(), "recommendation", recommendation.name()));
        findings.forEach(finding -> append(applicationId, assessment, policy.getId(), policy.getContentHash(), identity,
                "RECONCILIATION_EXCEPTION", Map.of("snapshotId", snapshotId, "finding", finding)));
        append(applicationId, assessment, policy.getId(), policy.getContentHash(), identity, "ASSESSMENT_COMPLETED",
                Map.of("snapshotId", snapshotId, "recommendation", recommendation.name()));
        return assessment;
    }

    private static List<Finding> reconcile(JsonNode declared, List<FieldInput> inputs) {
        var findings = new ArrayList<Finding>();
        var grouped = inputs.stream().filter(input -> input.field().value() != null)
                .collect(Collectors.groupingBy(FieldInput::key, java.util.TreeMap::new, Collectors.toList()));
        grouped.forEach((field, group) -> {
            var target = declared.get(field);
            var verified = group.stream().filter(FieldInput::verified).toList();
            var mismatch = verified.stream().filter(input -> target != null && !target.isNull()
                    && !equivalent(target, input.field().value())).map(FieldInput::reference).toList();
            if (!mismatch.isEmpty()) findings.add(new Finding("DECLARATION_MISMATCH", field, mismatch));
            if (verified.size() > 1 && verified.stream().anyMatch(input -> !equivalent(verified.getFirst().field().value(),
                    input.field().value()))) findings.add(new Finding("CONFLICTING_EVIDENCE", field, verified.stream().map(FieldInput::reference).toList()));
        });
        inputs.stream().filter(input -> input.field().validation() == Validation.FAILED)
                .forEach(input -> findings.add(new Finding("FIELD_VALIDATION_FAILED", input.key(), List.of(input.reference()))));
        return List.copyOf(findings);
    }

    @Transactional(readOnly = true)
    public Optional<Report> latest(Authentication actor, UUID id) {
        intake.requireReadable(actor, id, false);
        if (!canReadHistory(actor)) return Optional.empty();
        return reports(id, 1).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<Report> history(Authentication actor, UUID id) {
        intake.requireReadable(actor, id, false);
        if (!canReadHistory(actor)) {
            throw new org.springframework.security.access.AccessDeniedException("Assessment history requires case review or audit access");
        }
        return reports(id, 100);
    }

    public static boolean canReadHistory(Authentication actor) {
        return ActorIdentity.has(actor, "CASE_REVIEW") || ActorIdentity.has(actor, "AUDIT_VERIFY");
    }

    private List<Report> reports(UUID id, int limit) {
        return jdbc.query("""
                SELECT a.id,q.input_snapshot_id,q.breakdown,q.findings,a.assessed_at,a.recommendation,q.active_configuration
                FROM govaccess.assessment_evidence_quality q
                JOIN govaccess.assessment a ON q.assessment_id=a.id WHERE a.application_id=?
                ORDER BY a.assessed_at DESC,a.id DESC LIMIT ?
                """, (row, index) -> new Report(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                read(row.getString(3), Quality.class), List.of(read(row.getString(4), Finding[].class)),
                row.getObject(5, OffsetDateTime.class), row.getString(6), read(row.getString(7), Scoring.class)), id, limit);
    }

    private <T> T read(String json, Class<T> type) {
        try { return mapper.readerFor(type).with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(json); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Invalid stored assessment data", e); }
    }

    public static String validateDeclarations(String declarations, String policy) {
        if (declarations == null || declarations.isBlank()) return "{}";
        if (declarations.length() > 5000) throw new IllegalArgumentException("Declared values are too long.");
        var mapper = new ObjectMapper();
        try {
            JsonNode tree = mapper.readerFor(JsonNode.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(CanonicalJson.document(declarations));
            var document = mapper.readValue(policy, za.gov.govaccess.rules.model.RuleDocument.class);
            Set<String> allowed = document.rules().stream().flatMap(CaseAssessmentService::flatten)
                    .flatMap(rule -> Stream.of(rule.fieldKey(), rule.condition() == null ? null : rule.condition().fieldKey()))
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            if (tree.properties().stream().anyMatch(entry -> !allowed.contains(entry.getKey())
                    || !entry.getValue().isValueNode() || entry.getValue().asText().length() > 200)) {
                throw new IllegalArgumentException("Declare only scalar values for fields in the published policy.");
            }
            return CanonicalJson.write(tree);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Declare policy values as a JSON object.", e);
        }
    }

    private static Stream<Rule> flatten(Rule rule) {
        return Stream.concat(Stream.of(rule), rule.children().stream().flatMap(CaseAssessmentService::flatten));
    }

    private void append(UUID application, UUID assessment, UUID policy, String hash, ActorIdentity actor, String action, Object details) {
        audit.append(new Change(application, actor.id(), actor.role(), action, policy, hash, assessment,
                null, details, null, null, null));
    }

    private static double ratio(long count, int total) { return total == 0 ? 0 : (double) count / total; }

    private static boolean equivalent(JsonNode left, JsonNode right) {
        return left.isNumber() && right.isNumber() ? left.decimalValue().compareTo(right.decimalValue()) == 0 : left.equals(right);
    }
}
