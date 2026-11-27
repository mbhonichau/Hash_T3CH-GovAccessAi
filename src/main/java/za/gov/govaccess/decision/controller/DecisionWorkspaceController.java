package za.gov.govaccess.decision.controller;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.gov.govaccess.application.service.ApplicationIntakeService;
import za.gov.govaccess.assessment.service.AssessmentExplanationService;
import za.gov.govaccess.assessment.service.CaseAssessmentService;
import za.gov.govaccess.decision.service.DecisionWorkspaceService;
import za.gov.govaccess.decision.service.DecisionWorkspaceService.Outcome;
import za.gov.govaccess.document.service.ApplicationDocuments;
import za.gov.govaccess.security.ActorIdentity;

@Controller
class DecisionWorkspaceController {
    private final ApplicationIntakeService intake;
    private final CaseAssessmentService assessments;
    private final ApplicationDocuments documents;
    private final AssessmentExplanationService explanations;
    private final DecisionWorkspaceService decisions;

    DecisionWorkspaceController(ApplicationIntakeService intake, CaseAssessmentService assessments,
                                ApplicationDocuments documents, AssessmentExplanationService explanations,
                                DecisionWorkspaceService decisions) {
        this.intake = intake;
        this.assessments = assessments;
        this.documents = documents;
        this.explanations = explanations;
        this.decisions = decisions;
    }

    @PostMapping("/api/assessments/{id}/explanation")
    @ResponseBody
    AssessmentExplanationService.SavedExplanation explanationApi(Authentication actor, @PathVariable UUID id) {
        return explanations.generate(actor, id);
    }

    @GetMapping("/applications/{id}/decision")
    String workspace(Authentication actor, @PathVariable UUID id, Model model) {
        ActorIdentity.require(actor, "CASE_REVIEW");
        var detail = intake.detail(actor, id);
        var latest = assessments.latest(actor, id).orElse(null);
        model.addAttribute("applicationId", id);
        model.addAttribute("reference", detail.application().reference());
        model.addAttribute("latest", latest);
        model.addAttribute("closed", java.util.Set.of("DECISION_RECORDED", "CLOSED")
                .contains(detail.application().status().name()));
        model.addAttribute("officialDecisions", detail.decisions());
        var handoffs = decisions.handoffs(actor, id);
        model.addAttribute("handoffs", handoffs);
        model.addAttribute("availableDocuments", documents.list(actor, id).stream()
                .filter(item -> "SUCCEEDED".equals(item.state()) && !"IDENTITY_DOCUMENT".equals(item.type())).toList());
        model.addAttribute("savedExplanation", latest == null ? null
                : explanations.saved(actor, id, latest.assessmentId()).orElse(null));
        boolean escalation = !ActorIdentity.of(actor).role().equals("SUPERVISOR")
                && handoffs.stream().anyMatch(item -> "OVERRIDE_ESCALATION".equals(item.type()));
        model.addAttribute("awaitingSupervisor", escalation);
        model.addAttribute("canConfirm", ActorIdentity.has(actor, "DECISION_APPROVE") && !escalation);
        model.addAttribute("canAmend", ActorIdentity.has(actor, "ASSESSMENT_RUN") && ActorIdentity.has(actor, "EVIDENCE_CORRECT"));
        model.addAttribute("canOverride", ActorIdentity.has(actor, "DECISION_OVERRIDE"));
        model.addAttribute("canRequestEvidence", ActorIdentity.has(actor, "EVIDENCE_REQUEST"));
        return "application/decision";
    }

    @PostMapping("/applications/{id}/decision/explanation")
    String explanation(Authentication actor, @PathVariable UUID id, RedirectAttributes flash) {
        return perform(id, flash, () -> {
            var current = assessments.latest(actor, id).orElseThrow(() -> new IllegalArgumentException("Run an assessment first."));
            explanations.generate(actor, current.assessmentId());
            return "Explanation is ready.";
        });
    }

    @PostMapping("/applications/{id}/decision/confirm")
    String confirm(Authentication actor, @PathVariable UUID id, @RequestParam UUID assessmentId,
                   @RequestParam String reason, RedirectAttributes flash) {
        return perform(id, flash, () -> {
            decisions.confirm(actor, id, assessmentId, reason);
            return "Official decision recorded.";
        });
    }

    @PostMapping("/applications/{id}/decision/amend")
    String amend(Authentication actor, @PathVariable UUID id, @RequestParam UUID assessmentId,
                 @RequestParam UUID documentId, @RequestParam int documentVersion, @RequestParam String field,
                 @RequestParam String value, @RequestParam String reason, RedirectAttributes flash) {
        return perform(id, flash, () -> {
            decisions.amend(actor, id, assessmentId, documentId, documentVersion, field, value, reason);
            return "Evidence corrected and a replacement assessment recorded.";
        });
    }

    @PostMapping("/applications/{id}/decision/override")
    String override(Authentication actor, @PathVariable UUID id, @RequestParam UUID assessmentId,
                    @RequestParam Outcome outcome, @RequestParam String reason, RedirectAttributes flash) {
        return perform(id, flash, () -> decisions.override(actor, id, assessmentId, outcome, reason)
                ? "Official override recorded." : "Override referred to the assigned supervisor.");
    }

    @PostMapping("/applications/{id}/decision/evidence")
    String evidence(Authentication actor, @PathVariable UUID id, @RequestParam UUID assessmentId,
                    @RequestParam String reason, RedirectAttributes flash) {
        return perform(id, flash, () -> {
            decisions.requestEvidence(actor, id, assessmentId, reason);
            return "Evidence request saved for follow-up.";
        });
    }

    private static String perform(UUID id, RedirectAttributes flash, java.util.function.Supplier<String> action) {
        try {
            flash.addFlashAttribute("successMessage", action.get());
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/applications/" + id + "/decision";
    }
}
