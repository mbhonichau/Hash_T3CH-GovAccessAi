package za.gov.govaccess.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import za.gov.govaccess.application.entity.ApplicationRecord;
import za.gov.govaccess.policy.entity.PolicyVersion;

@Entity
@Table(name = "assessment")
public class Assessment {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false) private ApplicationRecord application;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_version_id") private PolicyVersion policyVersion;
    @Column(nullable = false, length = 32) private String recommendation;
    @Column(precision = 5, scale = 4) private BigDecimal confidence;
    @Column(nullable = false, columnDefinition = "text") private String summary;
    @Column(name = "assessed_by", nullable = false, length = 100) private String assessedBy;
    @Column(name = "assessed_at", nullable = false) private Instant assessedAt;

    protected Assessment() {}
    public UUID getId() { return id; }
    public String getRecommendation() { return recommendation; }
    public BigDecimal getConfidence() { return confidence; }
    public String getSummary() { return summary; }
    public Instant getAssessedAt() { return assessedAt; }
}
