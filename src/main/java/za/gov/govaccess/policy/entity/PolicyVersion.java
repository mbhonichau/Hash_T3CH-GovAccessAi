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
import java.time.Instant;
import java.time.LocalDate;
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

    private UUID authorId;
    private UUID publisherId;
    private String publisherName;
    private Instant publishedAt;
    private LocalDate effectiveFrom;
    private UUID supersedesId;
    @Column(length = 64)
    private String contentHash;

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
    public UUID getAuthorId() { return authorId; }
    public UUID getPublisherId() { return publisherId; }
    public String getPublisherName() { return publisherName; }
    public Instant getPublishedAt() { return publishedAt; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public UUID getSupersedesId() { return supersedesId; }
    public String getContentHash() { return contentHash; }

    public void recordAuthor(UUID author) {
        if (!"DRAFT".equals(status) || authorId != null) {
            throw new IllegalStateException("Policy author is already recorded");
        }
        authorId = author;
    }

    public void submitForReview(UUID actor) {
        requireStatus("DRAFT");
        if (authorId == null) {
            recordAuthor(java.util.Objects.requireNonNull(actor));
        }
        status = "REVIEW";
    }

    public void markTested() {
        requireStatus("REVIEW");
        status = "TESTED";
    }

    public void publish(UUID publisher, String publisherName, Instant time, LocalDate effectiveDate, UUID supersedes,
                        String canonicalContent, String hash) {
        requireStatus("TESTED");
        publisherId = java.util.Objects.requireNonNull(publisher);
        this.publisherName = java.util.Objects.requireNonNull(publisherName);
        publishedAt = java.util.Objects.requireNonNull(time);
        effectiveFrom = java.util.Objects.requireNonNull(effectiveDate);
        supersedesId = supersedes;
        content = java.util.Objects.requireNonNull(canonicalContent);
        contentHash = java.util.Objects.requireNonNull(hash);
        status = "PUBLISHED";
    }

    private void requireStatus(String expected) {
        if (!expected.equals(status)) {
            throw new IllegalStateException("Policy must be in " + expected + " to perform this action");
        }
    }
    public void setContent(String content) {
        if (!"DRAFT".equals(status) || this.content != null) {
            throw new IllegalStateException("Canonical policy content may only be assigned to a new draft");
        }
        this.content = java.util.Objects.requireNonNull(content);
    }

    public void reviseImportedDraft(String content) {
        if (!"DRAFT".equals(status) || !"AI_GENERATED".equals(origin) || ruleJson != null || this.content == null) {
            throw new IllegalStateException("Only a canonical AI-imported draft can be revised");
        }
        this.content = java.util.Objects.requireNonNull(content);
    }

    public void reviseManualCanonicalDraft(String content) {
        requireStatus("DRAFT");
        if (!"MANUAL".equals(origin) || ruleJson != null) {
            throw new IllegalStateException("Use the original review screen to edit this draft");
        }
        this.content = java.util.Objects.requireNonNull(content);
    }
}
