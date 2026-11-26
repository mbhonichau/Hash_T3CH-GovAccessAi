package za.gov.govaccess.decision.entity;

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
@Table(name = "official_decision")
public class OfficialDecision {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false) private ApplicationRecord application;
    @Column(nullable = false, length = 32) private String decision;
    @Column(nullable = false, columnDefinition = "text") private String rationale;
    @Column(name = "decided_by", nullable = false, length = 100) private String decidedBy;
    @Column(name = "decided_at", nullable = false) private Instant decidedAt;

    protected OfficialDecision() {}
    public UUID getId() { return id; }
    public String getDecision() { return decision; }
    public String getRationale() { return rationale; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
}
