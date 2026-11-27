package za.gov.govaccess.assessment.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.assessment.dto.AssessmentRun;
import za.gov.govaccess.assessment.repository.AssessmentRunRepository;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.audit.service.CanonicalJson;
import za.gov.govaccess.policy.service.PolicyVersionSelectionService;
import za.gov.govaccess.policy.service.RulePolicyService;
import za.gov.govaccess.rules.dto.VerifiedRuleEvidence;
import za.gov.govaccess.rules.service.RecommendationService;
import za.gov.govaccess.security.ActorIdentity;

@Service
public class PublishedPolicyAssessmentService {
    private final PolicyVersionSelectionService selection;
    private final RulePolicyService rules;
    private final RecommendationService recommendations;
    private final AssessmentRunRepository runs;
    private final BusinessAuditService audit;

    public PublishedPolicyAssessmentService(PolicyVersionSelectionService selection, RulePolicyService rules,
                                            RecommendationService recommendations, AssessmentRunRepository runs,
                                            BusinessAuditService audit) {
        this.selection = selection;
        this.rules = rules;
        this.recommendations = recommendations;
        this.runs = runs;
        this.audit = audit;
    }

    @Transactional
    public Optional<AssessmentRun> run(Authentication actor, String programmeCode, LocalDate applicationDate,
                                       List<VerifiedRuleEvidence> evidence, boolean manualReview) {
        ActorIdentity.require(actor, "ASSESSMENT_RUN");
        var assessor = ActorIdentity.of(actor);
        return selection.select(programmeCode, applicationDate).map(policy -> {
            var hash = CanonicalJson.sha256(CanonicalJson.document(policy.getContent()));
            if (!hash.equals(policy.getContentHash())) {
                throw new IllegalStateException("Published policy hash failed verification");
            }
            var document = rules.read(policy.getContent());
            var recommendation = recommendations.recommend(document, rules.evaluate(document, evidence), manualReview);
            var run = new AssessmentRun(UUID.randomUUID(), applicationDate,
                    OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS),
                    policy.getId(), policy.getVersionLabel(), hash, recommendation);
            runs.insert(run, assessor.id());
            audit.append(new Change(null, assessor.id(), assessor.role(), "ASSESSMENT_RUN", policy.getId(), hash,
                    run.id(), null, Map.of("recommendation", recommendation.name()), null, null, null));
            return run;
        });
    }

    @Transactional(readOnly = true)
    public Optional<AssessmentRun> find(Authentication actor, UUID id) {
        ActorIdentity.require(actor, "ASSESSMENT_RUN");
        return runs.findOwned(id, ActorIdentity.of(actor).id());
    }
}
