package za.gov.govaccess.policy.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.audit.service.CanonicalJson;
import za.gov.govaccess.policy.entity.PolicyVersion;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.rules.dto.VerifiedRuleEvidence;
import za.gov.govaccess.rules.service.RecommendationService;
import za.gov.govaccess.rules.service.RecommendationService.Recommendation;
import za.gov.govaccess.security.ActorIdentity;
import za.gov.govaccess.security.ManagedUserService;

@Service
public class PolicyLifecycleService {
    private final PolicyVersionRepository versions;
    private final RulePolicyService rules;
    private final RecommendationService recommendations;
    private final ManagedUserService users;
    private final BusinessAuditService audit;
    private final JdbcTemplate jdbc;
    private final boolean independentPublisher;

    public PolicyLifecycleService(PolicyVersionRepository versions, RulePolicyService rules,
                                  RecommendationService recommendations, ManagedUserService users,
                                  BusinessAuditService audit, JdbcTemplate jdbc,
                                  @Value("${govaccess.policy.require-independent-publisher:true}") boolean independentPublisher) {
        this.versions = versions;
        this.rules = rules;
        this.recommendations = recommendations;
        this.users = users;
        this.audit = audit;
        this.jdbc = jdbc;
        this.independentPublisher = independentPublisher;
    }

    public record SyntheticCase(String label, boolean synthetic, Recommendation expected,
                                boolean manualReview, List<VerifiedRuleEvidence> evidence) {}
    public record TestResult(UUID id, boolean passed, int totalCases, int passedCases) {}

    @Transactional
    public void submitForReview(UUID id, Authentication editor) {
        ActorIdentity.require(editor, "POLICY_EDIT");
        var actor = ActorIdentity.of(editor);
        var version = require(id, "DRAFT");
        var document = rules.read(version.getContent());
        if (document.rules().isEmpty()) {
            throw new IllegalArgumentException("Add at least one rule to the policy content before submitting it for review.");
        }
        rules.validate(document);
        version.submitForReview(actor.id());
        event(version, actor, "POLICY_SUBMITTED_FOR_REVIEW", Map.of("status", "DRAFT"), Map.of("status", "REVIEW"));
    }

    @Transactional
    public TestResult runTests(UUID id, Authentication reviewer, List<SyntheticCase> cases) {
        ActorIdentity.require(reviewer, "POLICY_TEST");
        var actor = ActorIdentity.of(reviewer);
        var version = require(id, "REVIEW");
        if (cases == null || cases.isEmpty() || cases.size() > 50 || cases.stream().anyMatch(item ->
                item == null || !item.synthetic() || item.expected() == null || item.evidence() == null
                        || item.evidence().size() > 100 || item.label() == null || item.label().isBlank()
                        || item.label().length() > 100)) {
            throw new IllegalArgumentException("Provide one to fifty labelled synthetic cases with expected recommendations");
        }
        var document = rules.read(version.getContent());
        int matched = 0;
        for (var fixture : cases) {
            var result = rules.evaluate(document, fixture.evidence());
            if (recommendations.recommend(document, result, fixture.manualReview()) == fixture.expected()) {
                matched++;
            }
        }
        var result = new TestResult(UUID.randomUUID(), matched == cases.size(), cases.size(), matched);
        var fixtureSnapshot = audit.storeProtectedSnapshot(cases);
        jdbc.update("""
                INSERT INTO govaccess.policy_test_run
                (id,policy_version_id,tester_id,content_hash,passed,total_cases,passed_cases,executed_at,fixture_snapshot_id)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, result.id(), id, actor.id(), hash(version), result.passed(), result.totalCases(),
                result.passedCases(), now(), fixtureSnapshot);
        if (result.passed()) {
            version.markTested();
        }
        event(version, actor, result.passed() ? "POLICY_TESTED" : "POLICY_TEST_FAILED", Map.of("status", "REVIEW"),
                Map.of("status", version.getStatus(), "testRunId", result.id(), "passedCases", matched,
                        "totalCases", cases.size()));
        return result;
    }

    @Transactional
    public void publish(UUID id, Authentication publisher, LocalDate effectiveFrom, UUID supersedes) {
        ActorIdentity.require(publisher, "POLICY_PUBLISH");
        var actor = ActorIdentity.of(publisher);
        var version = require(id, "TESTED");
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("An effective date is required");
        }
        if (independentPublisher && actor.id().equals(version.getAuthorId()) && users.otherReviewers(actor.id()) > 0) {
            throw new AccessDeniedException("Another reviewer must publish this author's policy");
        }
        String canonical = CanonicalJson.document(version.getContent());
        String hash = CanonicalJson.sha256(canonical);
        Boolean passed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM govaccess.policy_test_run
                    WHERE policy_version_id=? AND content_hash=? AND passed=true)
                """, Boolean.class, id, hash);
        if (!Boolean.TRUE.equals(passed)) {
            throw new IllegalStateException("A passed test run for the current content is required");
        }
        if (supersedes != null) {
            var previous = versions.findById(supersedes)
                    .orElseThrow(() -> new IllegalArgumentException("Superseded policy version was not found"));
            if (!"PUBLISHED".equals(previous.getStatus())
                    || !previous.getProgramme().getId().equals(version.getProgramme().getId())
                    || effectiveFrom.isBefore(previous.getEffectiveFrom())) {
                throw new IllegalArgumentException("Choose an earlier or overlapping published version of this programme");
            }
        }
        version.publish(actor.id(), publisher.getName(), now().toInstant(), effectiveFrom, supersedes, canonical, hash);
        event(version, actor, "POLICY_PUBLISHED", Map.of("status", "TESTED"),
                Map.of("status", "PUBLISHED", "effectiveFrom", effectiveFrom.toString()));
    }

    @Transactional
    public UUID forkPublished(UUID id, Authentication editor, String label) {
        ActorIdentity.require(editor, "POLICY_EDIT");
        var actor = ActorIdentity.of(editor);
        var original = require(id, "PUBLISHED");
        if (label == null || label.isBlank() || label.length() > 100
                || versions.findByProgrammeCodeAndVersionLabel(original.getProgramme().getCode(), label.strip()).isPresent()) {
            throw new IllegalArgumentException("Choose a unique draft label of up to 100 characters");
        }
        var draft = PolicyVersion.manualDraft(UUID.randomUUID(), original.getProgramme(), label.strip(), null);
        draft.recordAuthor(actor.id());
        draft.setContent(original.getContent());
        versions.saveAndFlush(draft);
        event(draft, actor, "POLICY_DRAFT_FORKED", Map.of("publishedVersionId", original.getId()),
                Map.of("status", "DRAFT"));
        return draft.getId();
    }

    @Transactional
    public void editDraft(UUID id, Authentication editor, String content) {
        ActorIdentity.require(editor, "POLICY_EDIT");
        if (content == null || content.isBlank() || content.length() > 200_000) {
            throw new IllegalArgumentException("Supply canonical policy JSON of up to 200000 characters");
        }
        var version = require(id, "DRAFT");
        var document = rules.read(content);
        rules.validate(document);
        String before = version.getContent();
        version.reviseManualCanonicalDraft(rules.write(document));
        event(version, ActorIdentity.of(editor), "POLICY_DRAFT_EDITED",
                Map.of("content", before), Map.of("content", version.getContent()));
    }
    private PolicyVersion require(UUID id, String status) {
        var version = versions.lockById(id).orElseThrow(() -> new IllegalArgumentException("Policy version not found"));
        if (!status.equals(version.getStatus())) {
            throw new IllegalStateException("Policy must be in " + status + " to perform this action");
        }
        return version;
    }

    private void event(PolicyVersion version, ActorIdentity actor, String action, Object prior, Object next) {
        audit.append(new Change(null, actor.id(), actor.role(), action, version.getId(), hash(version), null,
                prior, next, null, null, null));
    }

    private static String hash(PolicyVersion version) {
        return CanonicalJson.sha256(CanonicalJson.document(version.getContent()));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.ofInstant(Instant.now().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
