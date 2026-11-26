package za.gov.govaccess.rules.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import za.gov.govaccess.rules.entity.DocumentType;

public interface DocumentTypeRepository extends JpaRepository<DocumentType, String> {}
