package za.gov.govaccess.application.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.gov.govaccess.application.dto.ApplicationIntakeForm;
import za.gov.govaccess.application.service.ApplicationIntakeService;
import za.gov.govaccess.assessment.service.CaseAssessmentService;
import za.gov.govaccess.document.service.ApplicationDocuments;
import za.gov.govaccess.security.ActorIdentity;

@Controller
@RequestMapping("/applications")
public class ApplicationController {
    private final ApplicationIntakeService intake;
    private final ApplicationDocuments documents;
    private final CaseAssessmentService assessment;

    public ApplicationController(ApplicationIntakeService intake, ApplicationDocuments documents, CaseAssessmentService assessment) {
        this.intake = intake;
        this.documents = documents;
        this.assessment = assessment;
    }

    @GetMapping
    public String queue(Authentication actor, Model model) {
        model.addAttribute("applications", intake.queue(actor));
        model.addAttribute("canSubmitApplication", ApplicationIntakeService.canSubmit(actor));
        return "application/list";
    }

    @GetMapping("/new")
    public String createForm(Authentication actor, Model model) {
        if (!model.containsAttribute("applicationForm")) model.addAttribute("applicationForm", new ApplicationIntakeForm());
        model.addAttribute("programmes", intake.programmeOptions(actor));
        return "application/create";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("applicationForm") ApplicationIntakeForm form,
                         BindingResult binding, Authentication actor, Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            model.addAttribute("programmes", intake.programmeOptions(actor));
            return "application/create";
        }
        try {
            var created = intake.createCase(actor, form.getApplicantName(), form.getProgrammeCode(), form.getSummary(),
                    form.getDeclarations());
            redirect.addFlashAttribute("successMessage", "Case created. Reference: " + created.reference());
            return "redirect:/applications/" + created.id();
        } catch (IllegalArgumentException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return createForm(actor, model);
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, Authentication actor, Model model) {
        model.addAttribute("caseDetail", intake.detail(actor, id));
        model.addAttribute("caseDocuments", documents.list(actor, id));
        model.addAttribute("canUpload", ActorIdentity.has(actor, "DOCUMENT_UPLOAD"));
        model.addAttribute("canAssess", ActorIdentity.has(actor, "ASSESSMENT_RUN"));
        model.addAttribute("canUseDecisionWorkspace", ActorIdentity.has(actor, "CASE_REVIEW"));
        model.addAttribute("canReadAssessmentHistory", CaseAssessmentService.canReadHistory(actor));
        model.addAttribute("evidenceQuality", assessment.latest(actor, id).orElse(null));
        return "application/details";
    }

    @GetMapping("/{id}/assessments")
    public String assessmentHistory(@PathVariable UUID id, Authentication actor, Model model) {
        model.addAttribute("assessmentReports", assessment.history(actor, id));
        model.addAttribute("applicationId", id);
        return "application/assessment-history";
    }

    @PostMapping("/{id}/assessment")
    public String assess(@PathVariable UUID id, Authentication actor, RedirectAttributes redirect) {
        try {
            assessment.run(actor, id);
            redirect.addFlashAttribute("successMessage", "Assessment and evidence reconciliation recorded.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/applications/" + id;
    }

    @PostMapping("/assignment")
    public String assign(@RequestParam UUID caseId, Authentication actor, @RequestParam(required = false) UUID owner,
                         @RequestParam(required = false) UUID officer, @RequestParam(required = false) UUID supervisor,
                         @RequestParam(required = false) UUID escalation, RedirectAttributes redirect) {
        try {
            intake.assign(actor, caseId, owner, officer, supervisor, escalation);
            redirect.addFlashAttribute("message", "Application assignment updated.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }
}
