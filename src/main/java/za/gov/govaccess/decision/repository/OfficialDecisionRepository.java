package za.gov.govaccess.decision.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import za.gov.govaccess.decision.entity.OfficialDecision;

public interface OfficialDecisionRepository extends JpaRepository<OfficialDecision, UUID> {
    List<OfficialDecision> findAllByApplicationIdOrderByDecidedAtDesc(UUID applicationId);
}
