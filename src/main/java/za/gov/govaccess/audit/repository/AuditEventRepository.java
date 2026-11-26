package za.gov.govaccess.audit.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import za.gov.govaccess.audit.entity.AuditEvent;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    List<AuditEvent> findAllByApplicationIdOrderByOccurredAtDesc(UUID applicationId);
}
