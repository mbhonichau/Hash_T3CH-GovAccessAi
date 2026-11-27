package za.gov.govaccess.application.service;

import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import za.gov.govaccess.security.ActorIdentity;
import za.gov.govaccess.security.ManagedUserService;
import za.gov.govaccess.policy.service.PolicyVersionSelectionService;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.applicant.entity.Applicant;
import za.gov.govaccess.applicant.repository.ApplicantRepository;
import za.gov.govaccess.application.dto.ApplicationCaseView;
import za.gov.govaccess.application.entity.ApplicationRecord;
import za.gov.govaccess.application.enums.ApplicationStatus;
import za.gov.govaccess.application.repository.ApplicationRecordRepository;
import za.gov.govaccess.assessment.repository.AssessmentRepository;
import za.gov.govaccess.assessment.entity.Assessment;
import za.gov.govaccess.audit.entity.AuditEvent;
import za.gov.govaccess.audit.repository.AuditEventRepository;
import za.gov.govaccess.decision.repository.OfficialDecisionRepository;
import za.gov.govaccess.decision.entity.OfficialDecision;
import za.gov.govaccess.document.repository.ApplicationDocumentRepository;
import za.gov.govaccess.document.entity.ApplicationDocument;
import za.gov.govaccess.evidence.repository.EvidenceItemRepository;
import za.gov.govaccess.evidence.entity.EvidenceItem;
import za.gov.govaccess.programme.entity.Programme;
import za.gov.govaccess.programme.repository.ProgrammeRepository;

@Service
@Transactional(readOnly = true)
public class ApplicationIntakeService {
    private final ApplicantRepository applicants;
    private final ApplicationRecordRepository applications;
    private final ProgrammeRepository programmes;
    private final ApplicationDocumentRepository documents;
    private final EvidenceItemRepository evidence;
    private final AssessmentRepository assessments;
    private final OfficialDecisionRepository decisions;
    private final AuditEventRepository audit;
    private final BusinessAuditService businessAudit;
    private final PolicyVersionSelectionService policySelection;
    private final ManagedUserService users;

    public ApplicationIntakeService(ApplicantRepository applicants, ApplicationRecordRepository applications,
            ProgrammeRepository programmes, ApplicationDocumentRepository documents, EvidenceItemRepository evidence,
            AssessmentRepository assessments, OfficialDecisionRepository decisions, AuditEventRepository audit,
            BusinessAuditService businessAudit, PolicyVersionSelectionService policySelection, ManagedUserService users) {
        this.applicants = applicants;
        this.applications = applications;
        this.programmes = programmes;
        this.documents = documents;
        this.evidence = evidence;
        this.assessments = assessments;
        this.decisions = decisions;
        this.audit = audit;
        this.businessAudit = businessAudit;
        this.policySelection = policySelection;
        this.users = users;
    }

    public List<ApplicationCaseView> queue(Authentication actor) {
        if (!canReadCases(actor)) throw new AccessDeniedException("Case access is required");
        UUID user = ActorIdentity.of(actor).id();
        List<ApplicationRecord> visible;
        if (ActorIdentity.has(actor, "AUDIT_VERIFY")) {
            visible = applications.findAllByOrderByCreatedAtDesc();
        } else if (isRole(actor, "SUPERVISOR")) {
            visible = applications.findByTeamSupervisorIdOrEscalatedToIdOrderByCreatedAtDesc(user, user);
        } else if (ActorIdentity.has(actor, "CASE_REVIEW")) {
            visible = applications.findByAssignedOfficerIdOrderByCreatedAtDesc(user);
        } else {
            visible = applications.findByOwnerIdOrderByCreatedAtDesc(user);
        }
        return visible.stream().map(this::view).toList();
    }

    public ApplicationDetail detail(Authentication actor, UUID id) {
        var application = requireReadable(actor, id, false);
        var latestAssessment = assessments.findFirstByApplicationIdOrderByAssessedAtDesc(id).orElse(null);
        return new ApplicationDetail(view(application), documents.findAllByApplicationIdOrderByCreatedAtDesc(id),
                evidence.findAllByApplicationIdOrderByCreatedAtDesc(id), latestAssessment,
                decisions.findAllByApplicationIdOrderByDecidedAtDesc(id),
                ActorIdentity.has(actor, "CASE_REVIEW") || ActorIdentity.has(actor, "AUDIT_VERIFY")
                        ? audit.findAllByApplicationIdOrderByOccurredAtDesc(id) : List.of(),
                application.getPolicyVersionId());
    }

    @Transactional
    public ApplicationCaseView createCase(Authentication actor, String applicantName, String programmeCode, String summary) {
        return createCase(actor, applicantName, programmeCode, summary, null);
    }

    @Transactional
    public ApplicationCaseView createCase(Authentication actor, String applicantName, String programmeCode, String summary,
                                          String declarations) {
        if (!canSubmit(actor)) throw new AccessDeniedException("Application submission permission is required");
        Programme programme = programmes.findByCode(programmeCode)
                .orElseThrow(() -> new IllegalArgumentException("Choose a valid programme"));
        var date = LocalDate.now(ZoneId.of("Africa/Johannesburg"));
        var policy = policySelection.select(programmeCode, date)
                .orElseThrow(() -> new IllegalArgumentException("No published policy applies to this programme today."));
        var identity = ActorIdentity.of(actor);
        var applicant = applicants.save(new Applicant(applicantName.strip()));
        String reference = "GA-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        var application = new ApplicationRecord(reference, applicant, programme, summary.strip(), actor.getName());
        application.recordSubmission(identity.id(), date, policy.getId(),
                isRole(actor, "ASSESSMENT_OFFICER") ? identity.id() : null);
        application.recordDeclarations(za.gov.govaccess.assessment.service.CaseAssessmentService
                .validateDeclarations(declarations, policy.getContent()));
        applications.saveAndFlush(application);
        businessAudit.append(new Change(application.getId(), identity.id(), identity.role(), "CASE_CREATED",
                policy.getId(), policy.getContentHash(), null, null, null, null, null, null));
        return view(application);
    }

    public List<ProgrammeOption> programmeOptions(Authentication actor) {
        if (!canSubmit(actor)) throw new AccessDeniedException("Application submission permission is required");
        return programmes.findAll().stream().map(programme -> new ProgrammeOption(programme.getCode(), programme.getName())).toList();
    }

    public ApplicationDashboard dashboard(Authentication actor) {
        var visible = queue(actor);
        return new ApplicationDashboard(visible.size(),
                visible.stream().filter(value -> value.status() == ApplicationStatus.RECEIVED).count(),
                visible.stream().filter(value -> value.status() == ApplicationStatus.NEEDS_EVIDENCE).count(),
                visible.stream().limit(5).toList());
    }

    public static boolean canSubmit(Authentication actor) {
        return ActorIdentity.has(actor, "APPLICATION_SUBMIT") || ActorIdentity.has(actor, "PERM_APPLICATION_CREATE");
    }

    public static boolean canReadCases(Authentication actor) {
        return ActorIdentity.has(actor, "APPLICATION_VIEW_OWN") || ActorIdentity.has(actor, "CASE_REVIEW")
                || ActorIdentity.has(actor, "AUDIT_VERIFY") || ActorIdentity.has(actor, "PERM_APPLICATION_READ");
    }

    private static boolean isRole(Authentication actor, String role) {
        return actor.getAuthorities().stream().anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role));
    }

    private static boolean readable(Authentication actor, ApplicationRecord application) {
        if (!canReadCases(actor)) return false;
        UUID user = ActorIdentity.of(actor).id();
        if (ActorIdentity.has(actor, "AUDIT_VERIFY")) return true;
        if (isRole(actor, "SUPERVISOR")) {
            return user.equals(application.getTeamSupervisorId()) || user.equals(application.getEscalatedToId());
        }
        if (ActorIdentity.has(actor, "CASE_REVIEW")) return user.equals(application.getAssignedOfficerId());
        return user.equals(application.getOwnerId());
    }

    public ApplicationRecord requireReadable(Authentication actor, UUID id, boolean lock) {
        return (lock ? applications.lockById(id) : applications.findById(id)).filter(item -> readable(actor, item))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Application case not found"));
    }

    public ApplicationRecord requireUpload(Authentication actor, UUID id) {
        ActorIdentity.require(actor, "DOCUMENT_UPLOAD");
        return requireReadable(actor, id, true);
    }

    public ApplicationRecord requireVerification(Authentication actor, UUID id) {
        ActorIdentity.require(actor, "EVIDENCE_CORRECT");
        return requireReadable(actor, id, true);
    }

    @Transactional
    public void assign(Authentication actor, UUID caseId, UUID owner, UUID officer, UUID supervisor, UUID escalation) {
        ActorIdentity.require(actor, "USER_MANAGE");
        checkRole(owner, "APPLICANT");
        checkRole(officer, "ASSESSMENT_OFFICER");
        checkRole(supervisor, "SUPERVISOR");
        checkRole(escalation, "SUPERVISOR");
        var application = applications.lockById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Application case not found"));
        var previous = assignment(application);
        application.assign(owner == null ? application.getOwnerId() : owner, officer, supervisor, escalation);
        var identity = ActorIdentity.of(actor);
        businessAudit.append(new Change(caseId, identity.id(), identity.role(), "CASE_ASSIGNMENT_CHANGED",
                application.getPolicyVersionId(), null, null, previous, assignment(application), null, null, null));
    }

    private void checkRole(UUID id, String role) {
        if (id != null && !role.equals(users.roleOf(id))) {
            throw new IllegalArgumentException("The selected user has the wrong role for this assignment.");
        }
    }

    private static List<String> assignment(ApplicationRecord application) {
        return java.util.stream.Stream.of(application.getOwnerId(), application.getAssignedOfficerId(),
                application.getTeamSupervisorId(), application.getEscalatedToId())
                .map(id -> id == null ? "" : id.toString()).toList();
    }

    private ApplicationCaseView view(ApplicationRecord application) {
        return new ApplicationCaseView(application.getId(), application.getReference(),
                application.getApplicant().getFullName(), application.getProgramme().getName(),
                application.getStatus(), application.getRecommendation(), application.getSummary(),
                application.getCreatedBy(), application.getCreatedAt());
    }

    public record ProgrammeOption(String code, String name) {}
    public record ApplicationDashboard(long total, long received, long needsEvidence, List<ApplicationCaseView> recent) {}
    public record ApplicationDetail(ApplicationCaseView application, List<ApplicationDocument> documents,
                                    List<EvidenceItem> evidence, Assessment latestAssessment,
                                    List<OfficialDecision> decisions, List<AuditEvent> auditEvents, UUID policyVersionId) {}
}
