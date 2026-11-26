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
    @PreAuthorize("hasAuthority('PERM_DASHBOARD_READ')")
    String overview(Authentication authentication, Model model) {
        boolean canReadPolicies = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("PERM_POLICY_READ"));
        model.addAttribute("canReadPolicies", canReadPolicies);
        if (canReadPolicies) {
            var summary = policyAccess.summary();
            model.addAttribute("policyCount", summary.total());
            model.addAttribute("draftCount", summary.drafts());
            model.addAttribute("publishedCount", summary.published());
            model.addAttribute("policyVersions", policyAccess.recent());
        }
        boolean canReadApplications = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("PERM_APPLICATION_READ"));
        model.addAttribute("canReadApplications", canReadApplications);
        if (canReadApplications) model.addAttribute("applicationDashboard", applicationIntake.dashboard());
        return "home";
    }
}
