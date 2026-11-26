package za.gov.govaccess.programme.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import za.gov.govaccess.programme.entity.Programme;

public interface ProgrammeRepository extends JpaRepository<Programme, UUID> {
    Optional<Programme> findByCode(String code);
}
