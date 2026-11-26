package za.gov.govaccess.dashboard.controller;

import java.security.Principal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;

@Controller
class DashboardController {
    private final PolicyVersionRepository policies;

    DashboardController(PolicyVersionRepository policies) {
        this.policies = policies;
    }

    @GetMapping("/")
    String overview(Principal principal, Model model) {
        model.addAttribute("username", principal.getName());
        model.addAttribute("policyCount", policies.count());
        model.addAttribute("draftCount", policies.countByStatus("DRAFT"));
        model.addAttribute("publishedCount", policies.countByStatus("PUBLISHED"));
        model.addAttribute("policyVersions", policies.findTop5ByOrderByVersionLabelDescIdAsc());
        return "home";
    }
}
