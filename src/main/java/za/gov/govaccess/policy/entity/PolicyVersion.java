package za.gov.govaccess.policy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import za.gov.govaccess.programme.entity.Programme;

@Entity
@Table(name = "policy_version", uniqueConstraints = @UniqueConstraint(columnNames = {"programme_id", "version_label"}))
public class PolicyVersion {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "programme_id", nullable = false)
    private Programme programme;

    @Column(nullable = false, length = 100)
    private String versionLabel;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false, length = 32)
    private String origin;

    @Column(columnDefinition = "text")
    private String ruleJson;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    protected PolicyVersion() {}

    public static PolicyVersion manualDraft(UUID id, Programme programme, String versionLabel, String ruleJson) {
        return draft(id, programme, versionLabel, ruleJson, "MANUAL");
    }

    public static PolicyVersion aiDraft(UUID id, Programme programme, String versionLabel, String ruleJson) {
        return draft(id, programme, versionLabel, ruleJson, "AI_GENERATED");
    }

    private static PolicyVersion draft(UUID id, Programme programme, String versionLabel, String ruleJson, String origin) {
        var version = new PolicyVersion();
        version.id = id;
        version.programme = programme;
        version.versionLabel = versionLabel;
        version.status = "DRAFT";
        version.origin = origin;
        version.ruleJson = ruleJson;
        return version;
    }

    public UUID getId() { return id; }
    public Programme getProgramme() { return programme; }
    public String getVersionLabel() { return versionLabel; }
    public String getStatus() { return status; }
    public String getOrigin() { return origin; }
    public String getRuleJson() { return ruleJson; }
    public String getContent() { return content; }
    public void setContent(String content) {
        if (!"DRAFT".equals(status) || this.content != null) {
            throw new IllegalStateException("Canonical policy content may only be assigned to a new draft");
        }
        this.content = java.util.Objects.requireNonNull(content);
    }
}
