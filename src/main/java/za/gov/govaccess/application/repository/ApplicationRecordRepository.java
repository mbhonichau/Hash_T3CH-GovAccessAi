package za.gov.govaccess.application.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import za.gov.govaccess.application.entity.ApplicationRecord;
import za.gov.govaccess.application.enums.ApplicationStatus;

public interface ApplicationRecordRepository extends JpaRepository<ApplicationRecord, UUID> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from ApplicationRecord a where a.id = :id")
    java.util.Optional<ApplicationRecord> lockById(@org.springframework.data.repository.query.Param("id") UUID id);
    long countByStatus(ApplicationStatus status);

    @EntityGraph(attributePaths = {"applicant", "programme"})
    List<ApplicationRecord> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"applicant", "programme"})
    List<ApplicationRecord> findByOwnerIdOrderByCreatedAtDesc(UUID owner);

    @EntityGraph(attributePaths = {"applicant", "programme"})
    List<ApplicationRecord> findByAssignedOfficerIdOrderByCreatedAtDesc(UUID officer);

    @EntityGraph(attributePaths = {"applicant", "programme"})
    List<ApplicationRecord> findByTeamSupervisorIdOrEscalatedToIdOrderByCreatedAtDesc(UUID supervisor, UUID escalation);

    @EntityGraph(attributePaths = {"applicant", "programme"})
    Optional<ApplicationRecord> findById(UUID id);

    List<ApplicationRecord> findTop5ByOrderByCreatedAtDesc();

    boolean existsByReference(String reference);
}
