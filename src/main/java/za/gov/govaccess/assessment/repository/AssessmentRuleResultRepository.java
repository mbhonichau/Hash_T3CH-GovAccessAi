package za.gov.govaccess.assessment.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import za.gov.govaccess.assessment.entity.AssessmentRuleResult;

public interface AssessmentRuleResultRepository extends JpaRepository<AssessmentRuleResult, UUID> {
    List<AssessmentRuleResult> findAllByAssessmentId(UUID assessmentId);
}
