package za.gov.govaccess.policy.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.gov.govaccess.policy.service.FinancialPolicyPreviewService;
import za.gov.govaccess.policy.service.NsfasDemoPolicyService;
import za.gov.govaccess.rules.dto.FinancialScreenFacts;
import za.gov.govaccess.rules.enums.IncomePathway;
import za.gov.govaccess.rules.enums.VerifiedSassaGrant;
import za.gov.govaccess.rules.model.FinancialScreenPolicy;
import za.gov.govaccess.rules.model.FinancialScreenPolicy.GrantRule;
import za.gov.govaccess.rules.model.FinancialScreenPolicy.IncomeRule;
import za.gov.govaccess.security.PolicyWorkspaceAccess;

@Controller
@RequestMapping("/policies/nsfas-2025")
class NsfasPolicyController {
    private final NsfasDemoPolicyService demo;
    private final FinancialPolicyPreviewService preview;

    NsfasPolicyController(NsfasDemoPolicyService demo, FinancialPolicyPreviewService preview) {
        this.demo = demo;
        this.preview = preview;
    }

    @ModelAttribute("username")
    String username(Principal principal) {
        return principal.getName();
    }

    @ModelAttribute("canEditPolicy")
    boolean canEditPolicy(Authentication authentication, HttpServletRequest request) {
        return PolicyWorkspaceAccess.isEditing(authentication, request);
    }

    @GetMapping
    String show(@RequestParam(required = false) String version, Model model) {
        model.addAttribute("screen", new PreviewForm(null, null, null));
        return render(model, version);
    }

    @PostMapping("/load")
    String load() {
        try {
            demo.loadDraft();
        } catch (DataIntegrityViolationException e) {
            if (demo.findVersion().isEmpty()) {
                throw e;
            }
        }
        return "redirect:/policies/nsfas-2025";
    }

    @PostMapping("/preview")
    String preview(@Valid @ModelAttribute("screen") PreviewForm form, BindingResult binding,
                   @RequestParam(required = false) String version, Model model) {
        String label = version == null ? NsfasDemoPolicyService.VERSION_LABEL : version;
        if (!binding.hasErrors() && demo.findVersion(label).isPresent()) {
            var facts = new FinancialScreenFacts(form.income(), form.grant(), form.pathway());
            model.addAttribute("result", preview.preview(
                    NsfasDemoPolicyService.PROGRAMME_CODE, label, facts));
        }
        return render(model, label);
    }

    @PostMapping("/edit")
    String edit(@RequestParam String version, @RequestParam String versionLabel,
                @RequestParam BigDecimal standardIncome, @RequestParam BigDecimal disabilityIncome,
                @RequestParam(required = false) Set<VerifiedSassaGrant> grants, RedirectAttributes redirect) {
        var selected = demo.findVersion(version).filter(policy -> "DRAFT".equals(policy.getStatus()));
        String label = versionLabel.strip();
        try {
            var rules = preview.readRules(selected.orElseThrow(() -> new IllegalArgumentException("Select a draft.")));
            var grant = rules.qualifyingSassaGrant();
            if (grants == null || grants.isEmpty()) {
                throw new IllegalArgumentException("Select at least one qualifying grant.");
            }
            demo.saveDraft(label, new FinancialScreenPolicy(
                    new GrantRule(grant.ruleKey(), grants, grant.sourceSection(), grant.sourceQuote()),
                    withValue(rules.standardIncome(), standardIncome),
                    withValue(rules.disabilityIncome(), disabilityIncome)), false);
            redirect.addAttribute("version", label);
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            redirect.addFlashAttribute("editError",
                    "Enter a unique version label, valid income caps and at least one qualifying grant.");
            redirect.addAttribute("version", version);
        }
        return "redirect:/policies/nsfas-2025";
    }

    private static IncomeRule withValue(IncomeRule rule, BigDecimal value) {
        return new IncomeRule(rule.ruleKey(), value, rule.sourceSection(), rule.sourceQuote());
    }

    private String render(Model model, String label) {
        var drafts = demo.drafts();
        model.addAttribute("drafts", drafts);
        var selected = label == null
                ? demo.findVersion().or(() -> drafts.stream().findFirst()) : demo.findVersion(label);
        selected.ifPresent(version -> {
            model.addAttribute("version", version);
            model.addAttribute("rules", preview.readRules(version));
        });
        return "policy/nsfas-financial";
    }

    record PreviewForm(@DecimalMin("0.00") BigDecimal income, VerifiedSassaGrant grant, IncomePathway pathway) {}
}
