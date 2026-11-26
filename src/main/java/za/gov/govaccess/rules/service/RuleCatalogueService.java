package za.gov.govaccess.rules.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.rules.entity.DocumentType;
import za.gov.govaccess.rules.entity.FieldDefinition;
import za.gov.govaccess.rules.enums.FieldValueType;
import za.gov.govaccess.rules.repository.DocumentTypeRepository;
import za.gov.govaccess.rules.repository.FieldDefinitionRepository;

@Service
public class RuleCatalogueService {
    private final FieldDefinitionRepository fields;
    private final DocumentTypeRepository documentTypes;

    public RuleCatalogueService(FieldDefinitionRepository fields, DocumentTypeRepository documentTypes) {
        this.fields = fields;
        this.documentTypes = documentTypes;
    }

    public record Catalogue(Map<String, FieldDefinition> fields, Set<String> documentTypes) {}

    @Transactional(readOnly = true)
    public Catalogue catalogue() {
        return new Catalogue(fields.findAllByOrderByKey().stream()
                .collect(Collectors.toUnmodifiableMap(FieldDefinition::getKey, Function.identity())),
                documentTypes.findAll().stream().map(DocumentType::getKey).collect(Collectors.toUnmodifiableSet()));
    }

    @Transactional(readOnly = true)
    public List<DocumentType> documents() {
        return documentTypes.findAll(org.springframework.data.domain.Sort.by("key"));
    }

    @Transactional
    public DocumentType addDocument(String key, String label) {
        checkKey(key);
        checkLabel(label);
        if (documentTypes.existsById(key)) {
            throw new IllegalArgumentException("Document type key already exists");
        }
        return documentTypes.saveAndFlush(new DocumentType(key, label.strip()));
    }

    @Transactional
    public FieldDefinition addField(String key, String label, FieldValueType type, String unit, Set<String> sources) {
        checkKey(key);
        checkLabel(label);
        if (type == null || sources == null || sources.isEmpty() || unit != null && unit.length() > 64) {
            throw new IllegalArgumentException("Choose a field type and at least one valid source document type");
        }
        if (fields.existsById(key)) {
            throw new IllegalArgumentException("Field key already exists");
        }
        var documents = Set.copyOf(documentTypes.findAllById(sources));
        if (documents.size() != sources.size()) {
            throw new IllegalArgumentException("Unknown source document type");
        }
        return fields.saveAndFlush(new FieldDefinition(key, label.strip(), type, RuleDocumentValidator.blank(unit)
                ? null : unit.strip(), documents));
    }

    private static void checkKey(String key) {
        if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}")) {
            throw new IllegalArgumentException("Use a key of up to 100 letters, digits, underscores, dots or hyphens");
        }
    }

    private static void checkLabel(String label) {
        if (RuleDocumentValidator.blank(label) || label.length() > 255) {
            throw new IllegalArgumentException("Enter a label of up to 255 characters");
        }
    }
}
