package za.gov.govaccess.rules.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import za.gov.govaccess.rules.enums.FieldValueType;

@Entity
@Table(name = "field_definition")
public class FieldDefinition {
    @Id @Column(name = "field_key", length = 100)
    private String key;
    @Column(nullable = false)
    private String label;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private FieldValueType valueType;
    @Column(length = 64)
    private String unit;
    @ManyToMany
    @JoinTable(name = "field_definition_document_type",
            joinColumns = @JoinColumn(name = "field_key"),
            inverseJoinColumns = @JoinColumn(name = "type_key"))
    private Set<DocumentType> sourceDocumentTypes = new LinkedHashSet<>();

    protected FieldDefinition() {}

    public FieldDefinition(String key, String label, FieldValueType valueType, String unit, Set<DocumentType> sourceDocumentTypes) {
        this.key = key;
        this.label = label;
        this.valueType = valueType;
        this.unit = unit;
        this.sourceDocumentTypes.addAll(sourceDocumentTypes);
    }

    public String getKey() { return key; }
    public String getLabel() { return label; }
    public FieldValueType getValueType() { return valueType; }
    public String getUnit() { return unit; }
    public Set<DocumentType> getSourceDocumentTypes() { return Set.copyOf(sourceDocumentTypes); }
}
