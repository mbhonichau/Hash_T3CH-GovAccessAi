package za.gov.govaccess.dashboard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.Authentication;
import za.gov.govaccess.policy.service.PolicyRecordAccessService;
import za.gov.govaccess.application.service.ApplicationIntakeService;

@Controller
class DashboardController {
    private final PolicyRecordAccessService policyAccess;
    private final ApplicationIntakeService applicationIntake;

    DashboardController(PolicyRecordAccessService policyAccess, ApplicationIntakeService applicationIntake) {
        this.policyAccess = policyAccess;
        this.applicationIntake = applicationIntake;
    }

    @GetMapping("/")
    String overview(Principal principal, Model model) {
        if (principal instanceof org.springframework.security.core.Authentication authentication
                && authentication.getPrincipal() instanceof za.gov.govaccess.security.ManagedPrincipal user
                && user.role() == za.gov.govaccess.security.ManagedRole.APPLICANT) {
            return "auth/applicant-workspace";
        }
        model.addAttribute("username", principal.getName());
        model.addAttribute("policyCount", policies.count());
        model.addAttribute("draftCount", policies.countByStatus("DRAFT"));
        model.addAttribute("publishedCount", policies.countByStatus("PUBLISHED"));
        model.addAttribute("policyVersions", policies.findTop5ByOrderByVersionLabelDescIdAsc());
        return "home";
    }
}
