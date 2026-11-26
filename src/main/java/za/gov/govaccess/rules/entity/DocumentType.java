package za.gov.govaccess.rules.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "document_type")
public class DocumentType {
    @Id @Column(name = "type_key", length = 100)
    private String key;
    @Column(nullable = false)
    private String label;

    protected DocumentType() {}

    public DocumentType(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String getKey() { return key; }
    public String getLabel() { return label; }
}
