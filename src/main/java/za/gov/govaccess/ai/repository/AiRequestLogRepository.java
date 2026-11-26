package za.gov.govaccess.ai.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import za.gov.govaccess.ai.entity.AiRequestLog;

public interface AiRequestLogRepository extends JpaRepository<AiRequestLog, UUID> {}
