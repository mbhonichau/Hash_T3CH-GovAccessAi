package za.gov.govaccess.evidence.entity;

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
import za.gov.govaccess.document.entity.ApplicationDocument;

@Entity
@Table(name = "evidence_item")
public class EvidenceItem {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false) private ApplicationRecord application;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id") private ApplicationDocument document;
    @Column(name = "evidence_type", nullable = false, length = 100) private String evidenceType;
    @Column(name = "value_summary", nullable = false, columnDefinition = "text") private String valueSummary;
    @Column(name = "verification_status", nullable = false, length = 32) private String verificationStatus;
    @Column(name = "verified_by", length = 100) private String verifiedBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected EvidenceItem() {}
    public UUID getId() { return id; }
    public String getEvidenceType() { return evidenceType; }
    public String getValueSummary() { return valueSummary; }
    public String getVerificationStatus() { return verificationStatus; }
}
