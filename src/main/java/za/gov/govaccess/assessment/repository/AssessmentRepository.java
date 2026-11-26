package za.gov.govaccess.assessment.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import za.gov.govaccess.assessment.entity.Assessment;

public interface AssessmentRepository extends JpaRepository<Assessment, UUID> {
    Optional<Assessment> findFirstByApplicationIdOrderByAssessedAtDesc(UUID applicationId);
}
