package za.gov.govaccess.application.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import za.gov.govaccess.applicant.entity.Applicant;
import za.gov.govaccess.application.enums.ApplicationStatus;
import za.gov.govaccess.application.enums.CaseRecommendation;
import za.gov.govaccess.programme.entity.Programme;

@Entity
@Table(name = "case_application")
public class ApplicationRecord {
    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 32)
    private String reference;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "applicant_id", nullable = false)
    private Applicant applicant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "programme_id", nullable = false)
    private Programme programme;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApplicationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private CaseRecommendation recommendation;

    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private UUID ownerId;
    private java.time.LocalDate applicationDate;
    private UUID policyVersionId;
    private UUID assignedOfficerId;
    private UUID teamSupervisorId;
    private UUID escalatedToId;
    @Column(columnDefinition = "text")
    private String declaredValues;

    protected ApplicationRecord() {}

    public ApplicationRecord(String reference, Applicant applicant, Programme programme, String summary, String createdBy) {
        this.id = UUID.randomUUID();
        this.reference = reference;
        this.applicant = applicant;
        this.programme = programme;
        this.status = ApplicationStatus.RECEIVED;
        this.summary = summary;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    @PreUpdate
    void updateTimestamp() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public Applicant getApplicant() { return applicant; }
    public Programme getProgramme() { return programme; }
    public ApplicationStatus getStatus() { return status; }
    public CaseRecommendation getRecommendation() { return recommendation; }
    public String getSummary() { return summary; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getOwnerId() { return ownerId; }
    public java.time.LocalDate getApplicationDate() { return applicationDate; }
    public UUID getPolicyVersionId() { return policyVersionId; }
    public UUID getAssignedOfficerId() { return assignedOfficerId; }
    public UUID getTeamSupervisorId() { return teamSupervisorId; }
    public UUID getEscalatedToId() { return escalatedToId; }
    public String getDeclaredValues() { return declaredValues; }

    public void recordSubmission(UUID owner, java.time.LocalDate date, UUID policy, UUID officer) {
        if (policyVersionId != null) throw new IllegalStateException("The application policy is already fixed");
        ownerId = owner;
        applicationDate = java.util.Objects.requireNonNull(date);
        policyVersionId = java.util.Objects.requireNonNull(policy);
        assignedOfficerId = officer;
    }

    public void recordDeclarations(String values) { declaredValues = values; }

    public void recordAssessment(CaseRecommendation value) {
        requireOpen();
        recommendation = value;
        status = ApplicationStatus.ASSESSED;
    }

    public void recordOfficialDecision() {
        requireOpen();
        status = ApplicationStatus.DECISION_RECORDED;
    }

    public void requestEvidence() {
        requireOpen();
        status = ApplicationStatus.NEEDS_EVIDENCE;
    }

    public void escalateTo(UUID supervisor) {
        requireOpen();
        escalatedToId = java.util.Objects.requireNonNull(supervisor);
        status = ApplicationStatus.IN_REVIEW;
    }

    public void requireOpen() {
        if (status == ApplicationStatus.DECISION_RECORDED || status == ApplicationStatus.CLOSED) {
            throw new IllegalArgumentException("This application already has an official outcome.");
        }
    }

    public void assign(UUID owner, UUID officer, UUID teamSupervisor, UUID escalation) {
        ownerId = owner;
        assignedOfficerId = officer;
        teamSupervisorId = teamSupervisor;
        escalatedToId = escalation;
    }
}
