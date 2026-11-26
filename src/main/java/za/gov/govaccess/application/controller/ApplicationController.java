package za.gov.govaccess.application.controller;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.gov.govaccess.application.dto.ApplicationIntakeForm;
import za.gov.govaccess.application.service.ApplicationIntakeService;

@Controller
@RequestMapping("/applications")
public class ApplicationController {
    private final ApplicationIntakeService intake;

    public ApplicationController(ApplicationIntakeService intake) { this.intake = intake; }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_APPLICATION_READ')")
    public String queue(Model model) {
        model.addAttribute("applications", intake.queue());
        return "application/list";
    }

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PERM_APPLICATION_CREATE')")
    public String createForm(Model model) {
        if (!model.containsAttribute("applicationForm")) model.addAttribute("applicationForm", new ApplicationIntakeForm());
        model.addAttribute("programmes", intake.programmeOptions());
        return "application/create";
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_APPLICATION_CREATE')")
    public String create(@Valid @ModelAttribute("applicationForm") ApplicationIntakeForm form,
                         BindingResult binding, Principal principal, Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            model.addAttribute("programmes", intake.programmeOptions());
            return "application/create";
        }
        var created = intake.createCase(form.getApplicantName(), form.getProgrammeCode(), form.getSummary(), principal.getName());
        redirect.addFlashAttribute("successMessage", "Case created. Reference: " + created.reference());
        return "redirect:/applications/" + created.id();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_APPLICATION_READ')")
    public String detail(@PathVariable UUID id, Model model) {
        model.addAttribute("caseDetail", intake.detail(id));
        return "application/details";
    }
}
