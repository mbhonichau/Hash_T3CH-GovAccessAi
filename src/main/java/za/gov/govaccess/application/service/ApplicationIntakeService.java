package za.gov.govaccess.application.service;

import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
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

    public ApplicationIntakeService(ApplicantRepository applicants, ApplicationRecordRepository applications,
            ProgrammeRepository programmes, ApplicationDocumentRepository documents, EvidenceItemRepository evidence,
            AssessmentRepository assessments, OfficialDecisionRepository decisions, AuditEventRepository audit) {
        this.applicants = applicants;
        this.applications = applications;
        this.programmes = programmes;
        this.documents = documents;
        this.evidence = evidence;
        this.assessments = assessments;
        this.decisions = decisions;
        this.audit = audit;
    }

    @PreAuthorize("hasAuthority('PERM_APPLICATION_READ')")
    public List<ApplicationCaseView> queue() {
        return applications.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    @PreAuthorize("hasAuthority('PERM_APPLICATION_READ')")
    public ApplicationDetail detail(UUID id) {
        var application = applications.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Application case not found"));
        var latestAssessment = assessments.findFirstByApplicationIdOrderByAssessedAtDesc(id).orElse(null);
        return new ApplicationDetail(view(application), documents.findAllByApplicationIdOrderByCreatedAtDesc(id),
                evidence.findAllByApplicationIdOrderByCreatedAtDesc(id), latestAssessment,
                decisions.findAllByApplicationIdOrderByDecidedAtDesc(id),
                audit.findAllByApplicationIdOrderByOccurredAtDesc(id));
    }

    @PreAuthorize("hasAuthority('PERM_APPLICATION_CREATE')")
    @Transactional
    public ApplicationCaseView createCase(String applicantName, String programmeCode, String summary, String username) {
        Programme programme = programmes.findByCode(programmeCode)
                .orElseThrow(() -> new IllegalArgumentException("Choose a valid programme"));
        var applicant = applicants.save(new Applicant(applicantName.strip()));
        String reference = "GA-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        var application = applications.save(new ApplicationRecord(reference, applicant, programme, summary.strip(), username));
        audit.save(new AuditEvent(application, username, "CASE_CREATED", "Application case created."));
        return view(application);
    }

    @PreAuthorize("hasAuthority('PERM_APPLICATION_READ') or hasAuthority('PERM_APPLICATION_CREATE')")
    public List<ProgrammeOption> programmeOptions() {
        return programmes.findAll().stream().map(programme -> new ProgrammeOption(programme.getCode(), programme.getName())).toList();
    }

    @PreAuthorize("hasAuthority('PERM_DASHBOARD_READ') and hasAuthority('PERM_APPLICATION_READ')")
    public ApplicationDashboard dashboard() {
        return new ApplicationDashboard(applications.count(),
                applications.countByStatus(ApplicationStatus.RECEIVED),
                applications.countByStatus(ApplicationStatus.NEEDS_EVIDENCE),
                applications.findTop5ByOrderByCreatedAtDesc().stream().map(this::view).toList());
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
                                    List<OfficialDecision> decisions, List<AuditEvent> auditEvents) {}
}
