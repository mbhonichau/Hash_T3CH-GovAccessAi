package za.gov.govaccess.policy.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import za.gov.govaccess.programme.entity.Programme;

@Entity
@Table(name = "policy_version")
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

    @Column(nullable = false, columnDefinition = "text")
    private String ruleJson;

    protected PolicyVersion() {}

    public Programme getProgramme() { return programme; }
    public String getVersionLabel() { return versionLabel; }
    public String getStatus() { return status; }
    public String getOrigin() { return origin; }
    public String getRuleJson() { return ruleJson; }
}
