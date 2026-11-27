package za.gov.govaccess.decision.service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.application.entity.ApplicationRecord;
import za.gov.govaccess.application.service.ApplicationIntakeService;
import za.gov.govaccess.assessment.service.CaseAssessmentService;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.document.service.ApplicationDocuments;
import za.gov.govaccess.security.ActorIdentity;

@Service
public class DecisionWorkspaceService {
    private final ApplicationIntakeService intake;
    private final CaseAssessmentService assessments;
    private final ApplicationDocuments documents;
    private final BusinessAuditService audit;
    private final JdbcTemplate jdbc;
    private final Set<String> delegatedOfficers;
    private final Set<String> supervisorOnly;

    public DecisionWorkspaceService(ApplicationIntakeService intake, CaseAssessmentService assessments,
            ApplicationDocuments documents, BusinessAuditService audit, JdbcTemplate jdbc,
            @Value("${govaccess.decision.delegated-officers:}") String officers,
            @Value("${govaccess.decision.supervisor-only-overrides:DECLINED}") String supervisorOnly) {
        this.intake = intake;
        this.assessments = assessments;
        this.documents = documents;
        this.audit = audit;
        this.jdbc = jdbc;
        this.delegatedOfficers = tokens(officers);
        this.supervisorOnly = tokens(supervisorOnly);
    }

    public enum Outcome { APPROVED, DECLINED }
    public record Handoff(String type, String proposedDecision, String reason, OffsetDateTime createdAt) {}
    private record Workspace(ApplicationRecord application, String recommendation, UUID assessmentId, String policyHash) {}

    @Transactional(readOnly = true)
    public List<Handoff> handoffs(Authentication actor, UUID applicationId) {
        ActorIdentity.require(actor, "CASE_REVIEW");
        intake.requireReadable(actor, applicationId, false);
        return jdbc.query("""
                SELECT type,proposed_decision,reason,created_at FROM govaccess.decision_handoff
                WHERE application_id=? AND invalidated=false ORDER BY created_at DESC LIMIT 100
                """, (row, index) -> new Handoff(row.getString(1), row.getString(2), row.getString(3),
                row.getObject(4, OffsetDateTime.class)), applicationId);
    }

    @Transactional
    public void confirm(Authentication actor, UUID id, UUID assessmentId, String rationale) {
        ActorIdentity.require(actor, "DECISION_APPROVE");
        String reason = reason(rationale);
        var workspace = current(actor, id, assessmentId);
        var outcome = recommendedOutcome(workspace.recommendation());
        if (outcome == null) throw new IllegalArgumentException("This recommendation requires further review or an authorised override.");
        finalise(actor, workspace, outcome, reason, "DECISION_CONFIRMED");
    }

    @Transactional
    public UUID amend(Authentication actor, UUID id, UUID assessmentId, UUID documentId, int documentVersion,
                      String field, String value, String rationale) {
        ActorIdentity.require(actor, "ASSESSMENT_RUN");
        ActorIdentity.require(actor, "EVIDENCE_CORRECT");
        String reason = reason(rationale);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Enter a corrected evidence value.");
        var workspace = current(actor, id, assessmentId);
        var before = documents.get(actor, id, documentId).fields().get(field);
        documents.verify(actor, id, documentId, field, value, documentVersion);
        var after = documents.get(actor, id, documentId).fields().get(field);
        if (before != null && java.util.Objects.equals(before.value(), after.value())) {
            throw new IllegalArgumentException("Enter a different evidence value to amend the assessment.");
        }
        UUID newId = assessments.assess(actor, id);
        int drafts = jdbc.update("UPDATE govaccess.decision_handoff SET invalidated=true WHERE assessment_id=? AND invalidated=false", assessmentId);
        drafts += jdbc.update("""
                UPDATE govaccess.case_communication SET invalidated=true WHERE assessment_id=? AND state='DRAFT' AND invalidated=false
                """, assessmentId);
        jdbc.update("INSERT INTO govaccess.assessment_supersession VALUES (?,?)", assessmentId, newId);
        int explanations = jdbc.update("DELETE FROM govaccess.assessment_explanation_cache WHERE assessment_id=?", assessmentId);
        append(actor, workspace, "ASSESSMENT_AMENDED", Map.of("assessmentId", assessmentId, "field", field, "fieldState", before),
                Map.of("replacementAssessmentId", newId, "reason", reason, "invalidatedDrafts", drafts,
                        "invalidatedExplanations", explanations, "fieldState", after));
        return newId;
    }

    @Transactional
    public boolean override(Authentication actor, UUID id, UUID assessmentId, Outcome outcome, String rationale) {
        ActorIdentity.require(actor, "DECISION_OVERRIDE");
        String reason = reason(rationale);
        if (outcome == null) throw new IllegalArgumentException("Choose a different official outcome.");
        var workspace = current(actor, id, assessmentId);
        if (outcome == recommendedOutcome(workspace.recommendation())) {
            throw new IllegalArgumentException("Use confirm to record the recommended outcome.");
        }
        boolean supervisor = ActorIdentity.of(actor).role().equals("SUPERVISOR");
        if (supervisor || delegatedOfficers.contains(actor.getName()) && !supervisorOnly.contains(outcome.name())) {
            finalise(actor, workspace, outcome, reason, "DECISION_OVERRIDDEN");
            return true;
        }
        var application = workspace.application();
        UUID recipient = application.getEscalatedToId() == null ? application.getTeamSupervisorId() : application.getEscalatedToId();
        if (recipient == null || !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM govaccess.managed_user WHERE id=? AND role='SUPERVISOR' AND enabled=true)
                """, Boolean.class, recipient))) {
            throw new AccessDeniedException("This override exceeds delegated authority and requires an assigned supervisor.");
        }
        var previous = Map.of("status", application.getStatus().name(), "recommendation", workspace.recommendation());
        application.escalateTo(recipient);
        handoff(workspace, "OVERRIDE_ESCALATION", outcome.name(), reason, recipient);
        append(actor, workspace, "DECISION_OVERRIDE_ESCALATED", previous,
                Map.of("status", application.getStatus().name(), "supervisorId", recipient, "proposedDecision", outcome.name(), "reason", reason));
        return false;
    }

    @Transactional
    public void requestEvidence(Authentication actor, UUID id, UUID assessmentId, String rationale) {
        ActorIdentity.require(actor, "EVIDENCE_REQUEST");
        String reason = reason(rationale);
        var workspace = current(actor, id, assessmentId);
        var application = workspace.application();
        var previous = Map.of("status", application.getStatus().name(), "recommendation", workspace.recommendation());
        application.requestEvidence();
        UUID handoffId = handoff(workspace, "ADDITIONAL_EVIDENCE", null, reason, null);
        append(actor, workspace, "ADDITIONAL_EVIDENCE_REQUESTED", previous,
                Map.of("status", application.getStatus().name(), "handoffId", handoffId, "reason", reason));
    }

    private Workspace current(Authentication actor, UUID id, UUID assessmentId) {
        var application = intake.requireReadable(actor, id, true);
        application.requireOpen();
        var latest = assessments.latest(actor, id).orElseThrow(() -> new IllegalArgumentException("Run an assessment before recording an action."));
        if (!latest.assessmentId().equals(assessmentId)) throw new IllegalArgumentException("Refresh the workspace and select the latest assessment.");
        String hash = jdbc.queryForObject("SELECT policy_hash FROM govaccess.assessment WHERE id=?", String.class, assessmentId);
        if (hash == null) throw new IllegalArgumentException("Run a new assessment to record the pinned policy hash before deciding.");
        return new Workspace(application, latest.recommendation(), assessmentId, hash);
    }

    private void finalise(Authentication actor, Workspace workspace, Outcome outcome, String reason, String action) {
        var application = workspace.application();
        if (!ActorIdentity.of(actor).role().equals("SUPERVISOR") && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM govaccess.decision_handoff
                WHERE application_id=? AND type='OVERRIDE_ESCALATION' AND invalidated=false)
                """, Boolean.class, application.getId()))) {
            throw new AccessDeniedException("An assigned supervisor must resolve this escalation.");
        }
        var previous = Map.of("status", application.getStatus().name(), "recommendation", workspace.recommendation());
        application.recordOfficialDecision();
        UUID decisionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO govaccess.official_decision (id,application_id,assessment_id,decision,rationale,decided_by,decided_at)
                VALUES (?,?,?,?,?,?,?)
                """, decisionId, application.getId(), workspace.assessmentId(), outcome.name(), reason, actor.getName(), OffsetDateTime.now());
        jdbc.update("UPDATE govaccess.decision_handoff SET invalidated=true WHERE application_id=? AND invalidated=false",
                application.getId());
        append(actor, workspace, action, previous,
                Map.of("decisionId", decisionId, "decision", outcome.name(), "status", application.getStatus().name(), "reason", reason));
    }

    private UUID handoff(Workspace workspace, String type, String outcome, String reason, UUID recipient) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO govaccess.decision_handoff (id,application_id,assessment_id,type,proposed_decision,reason,owner_id,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, workspace.application().getId(), workspace.assessmentId(), type, outcome, reason, recipient, OffsetDateTime.now());
        return id;
    }

    private void append(Authentication actor, Workspace workspace, String action, Object previous, Object next) {
        var identity = ActorIdentity.of(actor);
        audit.append(new Change(workspace.application().getId(), identity.id(), identity.role(), action,
                workspace.application().getPolicyVersionId(), workspace.policyHash(), workspace.assessmentId(),
                previous, next, null, null, null));
    }

    private static String reason(String value) {
        if (value == null || value.isBlank() || value.length() > 1000) {
            throw new IllegalArgumentException("Enter a reason of up to 1000 characters.");
        }
        return value.strip();
    }

    private static Outcome recommendedOutcome(String recommendation) {
        return switch (recommendation) {
            case "LIKELY_ELIGIBLE" -> Outcome.APPROVED;
            case "NOT_ELIGIBLE" -> Outcome.DECLINED;
            default -> null;
        };
    }

    private static Set<String> tokens(String value) {
        return Arrays.stream(value.split(",")).map(String::strip).filter(item -> !item.isBlank()).collect(Collectors.toUnmodifiableSet());
    }
}
