package za.gov.govaccess.policy.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import za.gov.govaccess.policy.entity.PolicyVersion;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PolicyVersion p where p.id = :id")
    Optional<PolicyVersion> lockById(UUID id);
    Optional<PolicyVersion> findByProgrammeCodeAndVersionLabel(String programmeCode, String versionLabel);

    List<PolicyVersion> findByProgrammeCodeAndStatusOrderByVersionLabel(String programmeCode, String status);
    @EntityGraph(attributePaths = "programme")
    List<PolicyVersion> findAllByOrderByVersionLabelAsc();

    long countByStatus(String status);

    @EntityGraph(attributePaths = "programme")
    List<PolicyVersion> findTop5ByOrderByVersionLabelDescIdAsc();
}
