package za.gov.govaccess.evidence.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import za.gov.govaccess.evidence.entity.EvidenceItem;

public interface EvidenceItemRepository extends JpaRepository<EvidenceItem, UUID> {
    List<EvidenceItem> findAllByApplicationIdOrderByCreatedAtDesc(UUID applicationId);
}
