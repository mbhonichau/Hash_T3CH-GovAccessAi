package za.gov.govaccess.applicant.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import za.gov.govaccess.applicant.entity.Applicant;

public interface ApplicantRepository extends JpaRepository<Applicant, UUID> {}
