package za.gov.govaccess.policy.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.policy.service.PolicyLifecycleService;
import za.gov.govaccess.policy.service.PolicyLifecycleService.SyntheticCase;
import za.gov.govaccess.policy.service.PolicyVersionSelectionService;
import za.gov.govaccess.policy.service.RulePolicyService;
import za.gov.govaccess.programme.repository.ProgrammeRepository;
import za.gov.govaccess.rules.model.RuleDocument;
import za.gov.govaccess.security.ActorIdentity;
import za.gov.govaccess.security.PolicyWorkspaceAccess;

@Controller
@RequestMapping("/policies/lifecycle")
class PolicyLifecycleController {
    private final PolicyVersionRepository versions;
    private final PolicyLifecycleService lifecycle;
    private final PolicyVersionSelectionService selection;
    private final ObjectMapper mapper;
    private final ProgrammeRepository programmes;
    private final RulePolicyService rules;

    PolicyLifecycleController(PolicyVersionRepository versions, PolicyLifecycleService lifecycle,
                              PolicyVersionSelectionService selection, ObjectMapper mapper,
                              ProgrammeRepository programmes, RulePolicyService rules) {
        this.versions = versions;
        this.lifecycle = lifecycle;
        this.selection = selection;
        this.mapper = mapper;
        this.programmes = programmes;
        this.rules = rules;
    }

    @ModelAttribute
    void workspace(Authentication actor, HttpServletRequest request, Model model) {
        model.addAttribute("username", actor.getName());
        model.addAttribute("lifecyclePage", true);
        model.addAttribute("canEditPolicy", PolicyWorkspaceAccess.isEditing(actor, request));
        model.addAttribute("canTestPolicy", ActorIdentity.has(actor, "POLICY_TEST"));
        model.addAttribute("canPublishPolicy", ActorIdentity.has(actor, "POLICY_PUBLISH"));
    }

    @GetMapping
    String versions(Model model) {
        model.addAttribute("versions", versions.findAllByOrderByVersionLabelAsc());
        return "policy/lifecycle-list";
    }

    @GetMapping("/programmes")
    String programmes(Model model) {
        model.addAttribute("programmes", programmes.findAll(org.springframework.data.domain.Sort.by("name")));
        return "policy/programmes";
    }

    @PostMapping("/programmes")
    String createProgramme(@RequestParam String code, @RequestParam String name, RedirectAttributes flash) {
        try {
            rules.createProgramme(code.strip(), name);
            flash.addFlashAttribute("successMessage", "Programme created.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/policies/lifecycle/programmes";
    }

    @PostMapping("/drafts")
    String createDraft(@RequestParam String programme, @RequestParam String label, RedirectAttributes flash) {
        try {
            var version = rules.saveDraft(programme, label.strip(), new RuleDocument(RuleDocument.CURRENT_VERSION, List.of(), null));
            return "redirect:/policies/lifecycle/" + version.getId();
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/policies/lifecycle/programmes";
        }
    }

    @GetMapping("/{id}")
    String version(@PathVariable UUID id, Model model) {
        model.addAttribute("version", versions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        return "policy/lifecycle";
    }

    @PostMapping("/{id}/review")
    String review(@PathVariable UUID id, Authentication actor, RedirectAttributes redirect) {
        return perform(id, redirect, () -> {
            lifecycle.submitForReview(id, actor);
            return "Submitted for review.";
        });
    }

    @PostMapping("/{id}/test")
    String test(@PathVariable UUID id, Authentication actor, @RequestParam String cases, RedirectAttributes redirect) {
        return perform(id, redirect, () -> {
            var result = lifecycle.runTests(id, actor, decode(cases));
            return result.passed() ? "All synthetic cases passed. The policy is tested."
                    : result.passedCases() + " of " + result.totalCases() + " cases passed. The policy remains in review.";
        });
    }

    @PostMapping("/{id}/publish")
    String publish(@PathVariable UUID id, Authentication actor, @RequestParam LocalDate effectiveFrom,
                   @RequestParam(required = false) UUID supersedes, RedirectAttributes redirect) {
        return perform(id, redirect, () -> {
            lifecycle.publish(id, actor, effectiveFrom, supersedes);
            return "Published. The policy version is now immutable.";
        });
    }

    @PostMapping("/{id}/fork")
    String fork(@PathVariable UUID id, Authentication actor, @RequestParam String label, RedirectAttributes redirect) {
        try {
            UUID draft = lifecycle.forkPublished(id, actor, label);
            redirect.addFlashAttribute("message", "Created a new editable draft.");
            return "redirect:/policies/lifecycle/" + draft;
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/policies/lifecycle/" + id;
        }
    }

    @PostMapping("/{id}/edit")
    String edit(@PathVariable UUID id, Authentication actor, @RequestParam String content, RedirectAttributes redirect) {
        return perform(id, redirect, () -> {
            lifecycle.editDraft(id, actor, content);
            return "Draft content saved.";
        });
    }

    @PostMapping("/{id}/transition")
    String transition(@PathVariable UUID id, Authentication actor, @RequestParam LocalDate startsOn,
                      @RequestParam LocalDate endsOn, @RequestParam int priority, RedirectAttributes redirect) {
        return perform(id, redirect, () -> {
            selection.configureTransition(actor, id, startsOn, endsOn, priority);
            return "Transitional date window saved.";
        });
    }

    private List<SyntheticCase> decode(String json) {
        if (json == null || json.length() > 200_000) {
            throw new IllegalArgumentException("Supply synthetic cases in a JSON array of up to 200000 characters");
        }
        try {
            return mapper.readerForListOf(SyntheticCase.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Provide a valid JSON array of synthetic test cases", e);
        }
    }

    private static String perform(UUID id, RedirectAttributes redirect, Supplier<String> action) {
        try {
            redirect.addFlashAttribute("message", action.get());
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/policies/lifecycle/" + id;
    }
}
