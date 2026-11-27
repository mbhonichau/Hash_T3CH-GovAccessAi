package za.gov.govaccess.audit.entity;

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
@Table(name = "audit_event")
public class AuditEvent {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id") private ApplicationRecord application;
    @Column(name = "actor_username", nullable = false, length = 100) private String actorUsername;
    @Column(nullable = false, length = 64) private String action;
    @Column(nullable = false, columnDefinition = "text") private String detail;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(name = "actor_role", length = 100) private String actorRole;

    protected AuditEvent() {}
    public AuditEvent(ApplicationRecord application, String actorUsername, String action, String detail) {
        this.id = UUID.randomUUID();
        this.application = application;
        this.actorUsername = actorUsername;
        this.action = action;
        this.detail = detail;
        this.occurredAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getActorUsername() { return actorUsername; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getActorRole() { return actorRole; }
}
