package za.gov.govaccess.ai.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.gov.govaccess.ai.entity.AiCacheEntry;

public interface AiCacheRepository extends JpaRepository<AiCacheEntry, String> {}
