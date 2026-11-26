package za.gov.govaccess.policy.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import za.gov.govaccess.policy.entity.PolicyVersion;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, UUID> {
    Optional<PolicyVersion> findByProgrammeCodeAndVersionLabel(String programmeCode, String versionLabel);

    List<PolicyVersion> findByProgrammeCodeAndStatusOrderByVersionLabel(String programmeCode, String status);

    long countByStatus(String status);

    @EntityGraph(attributePaths = "programme")
    List<PolicyVersion> findTop5ByOrderByVersionLabelDescIdAsc();
}
