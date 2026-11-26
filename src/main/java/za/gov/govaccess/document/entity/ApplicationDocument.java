package za.gov.govaccess.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import za.gov.govaccess.application.entity.ApplicationRecord;

@Entity
@Table(name = "application_document")
public class ApplicationDocument {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private ApplicationRecord application;
    @Column(name = "original_filename", nullable = false, length = 255) private String originalFilename;
    @Column(name = "content_type", length = 150) private String contentType;
    @Column(name = "storage_key", nullable = false, length = 500) private String storageKey;
    @Column(length = 64) private String sha256;
    @Column(name = "processing_status", nullable = false, length = 32) private String processingStatus;
    @Column(name = "uploaded_by", nullable = false, length = 100) private String uploadedBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected ApplicationDocument() {}
    public UUID getId() { return id; }
    public ApplicationRecord getApplication() { return application; }
    public String getOriginalFilename() { return originalFilename; }
    public String getProcessingStatus() { return processingStatus; }
}
