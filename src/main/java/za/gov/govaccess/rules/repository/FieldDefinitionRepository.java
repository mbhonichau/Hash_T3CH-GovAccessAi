package za.gov.govaccess.rules.repository;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import za.gov.govaccess.rules.entity.FieldDefinition;

public interface FieldDefinitionRepository extends JpaRepository<FieldDefinition, String> {
    @EntityGraph(attributePaths = "sourceDocumentTypes")
    List<FieldDefinition> findAllByOrderByKey();
}
