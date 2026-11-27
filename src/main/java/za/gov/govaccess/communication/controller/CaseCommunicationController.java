package za.gov.govaccess.communication.controller;

import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.gov.govaccess.communication.service.CaseCommunicationService;
import za.gov.govaccess.programme.repository.ProgrammeRepository;
import za.gov.govaccess.security.ActorIdentity;

@Controller
class CaseCommunicationController {
    private final CaseCommunicationService service;
    private final ProgrammeRepository programmes;

    CaseCommunicationController(CaseCommunicationService service, ProgrammeRepository programmes) {
        this.service = service;
        this.programmes = programmes;
    }

    @GetMapping("/applications/{application}/communications")
    String list(Authentication actor, @PathVariable UUID application, Model model) {
        model.addAttribute("applicationId", application);
        model.addAttribute("messages", service.messages(actor, application));
        model.addAttribute("canRequestEvidence", ActorIdentity.has(actor, "EVIDENCE_REQUEST"));
        model.addAttribute("canApproveNotice", ActorIdentity.has(actor, "NOTICE_APPROVE"));
        return "application/communications";
    }

    @GetMapping("/applications/{application}/communications/{id}")
    String view(Authentication actor, @PathVariable UUID application, @PathVariable UUID id, Model model) {
        var message = service.view(actor, application, id);
        model.addAttribute("message", message);
        model.addAttribute("staff", CaseCommunicationService.canReview(actor));
        model.addAttribute("canRequestEvidence", ActorIdentity.has(actor, "EVIDENCE_REQUEST"));
        model.addAttribute("canApproveNotice", ActorIdentity.has(actor, "NOTICE_APPROVE"));
        model.addAttribute("noticeConfigured", CaseCommunicationService.NOTICE.equals(message.kind()) && service.hasProcedure(actor, application));
        model.addAttribute("translationLanguage", service.translationLanguage());
        model.addAttribute("readingTarget", service.readingTarget());
        return "application/communication";
    }

    @PostMapping("/applications/{application}/communications")
    String draft(Authentication actor, @PathVariable UUID application, @RequestParam String kind, RedirectAttributes flash) {
        String path = base(application);
        try {
            return "redirect:" + path + "/" + service.draft(actor, application, kind).id();
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:" + path;
        }
    }

    @PostMapping("/applications/{application}/communications/{id}/{action}")
    String action(Authentication actor, @PathVariable UUID application, @PathVariable UUID id, @PathVariable String action,
                  @RequestParam(required = false) String reason, RedirectAttributes flash) {
        try {
            String success = switch (action) {
                case "approve" -> { service.approve(actor, application, id); yield "English notice approved."; }
                case "send" -> { service.send(actor, application, id, reason); yield "Message sent to the applicant workspace."; }
                case "translate" -> service.translate(actor, application, id) ? "Machine translation is ready for review."
                        : "Translation could not be verified. The approved English notice is available.";
                case "approve-translation" -> { service.approveTranslation(actor, application, id); yield "Translation reviewed and approved."; }
                default -> throw new IllegalArgumentException("Choose an available communication action.");
            };
            flash.addFlashAttribute("successMessage", success);
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:" + base(application) + "/" + id;
    }

    @GetMapping("/policies/lifecycle/notice-configuration")
    String configuration(Authentication actor, @RequestParam(required = false) UUID programme, Model model) {
        ActorIdentity.require(actor, "POLICY_PUBLISH");
        model.addAttribute("programmes", programmes.findAll(Sort.by("name")));
        model.addAttribute("selectedProgramme", programme);
        model.addAttribute("procedure", programme == null ? null : service.procedure(programme).orElse(null));
        return "policy/notice-configuration";
    }

    @PostMapping("/policies/lifecycle/notice-configuration")
    String approveProcedure(Authentication actor, @RequestParam UUID programme, @RequestParam String procedure,
                            RedirectAttributes flash) {
        if (!programmes.existsById(programme)) {
            ActorIdentity.require(actor, "POLICY_PUBLISH");
            flash.addFlashAttribute("errorMessage", "Choose a valid programme.");
        } else try {
            service.approveProcedure(actor, programme, procedure);
            flash.addFlashAttribute("successMessage", "Programme review or appeal wording approved.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/policies/lifecycle/notice-configuration?programme=" + programme;
    }

    private static String base(UUID application) { return "/applications/" + application + "/communications"; }
}
