package za.gov.govaccess.policy.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.gov.govaccess.document.service.PolicySourceReader;
import za.gov.govaccess.policy.repository.PolicyImportRepository.Source;
import za.gov.govaccess.policy.service.PolicyImportJobs;
import za.gov.govaccess.programme.repository.ProgrammeRepository;
import za.gov.govaccess.security.PolicyWorkspaceAccess;

@Controller
class PolicyDocumentImportController {
    private final PolicyImportJobs jobs;
    private final ProgrammeRepository programmes;

    PolicyDocumentImportController(PolicyImportJobs jobs, ProgrammeRepository programmes) {
        this.jobs = jobs;
        this.programmes = programmes;
    }

    @ModelAttribute
    void workspace(Authentication authentication, HttpServletRequest request, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("documentImportPage", true);
        model.addAttribute("canEditPolicy", PolicyWorkspaceAccess.isEditing(authentication, request));
    }

    @GetMapping("/policies/imports")
    String form(Principal principal, Model model) {
        model.addAttribute("programmes", programmes.findAll(Sort.by("name")));
        model.addAttribute("jobs", jobs.recent(principal));
        return "policy/document-import";
    }

    @PostMapping("/policies/imports")
    String enqueue(Principal principal, @RequestParam String programmeCode, @RequestParam String versionLabel,
                   @RequestParam String documentTitle, @RequestParam(required = false) String text,
                   @RequestParam(required = false) MultipartFile pdf, RedirectAttributes redirect) {
        try {
            if (pdf != null && pdf.getSize() > PolicySourceReader.MAX_PDF_BYTES) {
                throw new IllegalArgumentException("The PDF exceeds the 8 MB limit.");
            }
            byte[] bytes = pdf == null || pdf.isEmpty() ? null : pdf.getBytes();
            var job = jobs.enqueue(principal, programmeCode, versionLabel, documentTitle,
                    new Source(text == null || text.isBlank() ? null : text, bytes));
            return "redirect:/policies/imports/" + job.id();
        } catch (IOException e) {
            redirect.addFlashAttribute("importError", "The uploaded file could not be read.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("importError", e.getMessage());
        }
        return "redirect:/policies/imports";
    }

    @GetMapping("/policies/imports/{id}")
    String progress(Principal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("job", jobs.owned(id, principal));
        model.addAttribute("instructionLikeContent", jobs.instructionLike(id));
        return "policy/import-progress";
    }

    @GetMapping("/api/policies/imports/{id}")
    @ResponseBody
    Map<String, Object> poll(Principal principal, @PathVariable UUID id) {
        var job = jobs.owned(id, principal);
        return Map.of("status", job.status(), "completedChunks", job.completedChunks(),
                "totalChunks", job.totalChunks(), "failure", job.failure() == null ? "" : job.failure());
    }

    @GetMapping("/policies/imports/{id}/review")
    String review(Principal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("job", jobs.owned(id, principal));
        model.addAttribute("instructionLikeContent", jobs.instructionLike(id));
        model.addAttribute("review", jobs.review(id, principal));
        return "policy/import-review";
    }

    @PostMapping("/policies/imports/{id}/edit")
    String edit(Principal principal, @PathVariable UUID id, @RequestParam String ruleCode,
                @RequestParam String expectedValue, RedirectAttributes redirect) {
        try {
            jobs.edit(id, principal, ruleCode, expectedValue);
            redirect.addFlashAttribute("editSaved", "Draft rule saved.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("editError", e.getMessage());
        }
        return "redirect:/policies/imports/" + id + "/review";
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    void missing() {}
}
