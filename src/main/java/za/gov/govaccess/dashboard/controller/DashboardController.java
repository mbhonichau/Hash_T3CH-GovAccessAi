package za.gov.govaccess.dashboard.controller;

import org.springframework.stereotype.Controller;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.Authentication;
import za.gov.govaccess.policy.service.PolicyRecordAccessService;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.security.ActorIdentity;
import za.gov.govaccess.application.service.ApplicationIntakeService;

@Controller
class DashboardController {
    private final PolicyRecordAccessService policyAccess;
    private final ApplicationIntakeService applicationIntake;
    private final PolicyVersionRepository policies;
    private final ObjectProvider<HealthEndpoint> healthEndpoint;

    DashboardController(PolicyRecordAccessService policyAccess, ApplicationIntakeService applicationIntake,
                        PolicyVersionRepository policies, ObjectProvider<HealthEndpoint> healthEndpoint) {
        this.policyAccess = policyAccess;
        this.applicationIntake = applicationIntake;
        this.policies = policies;
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/system/status")
    String systemStatus(Model model) {
        var endpoint = healthEndpoint.getIfAvailable();
        String status = endpoint == null ? "UNKNOWN" : endpoint.health().getStatus().getCode();
        model.addAttribute("systemStatus", switch (status) {
            case "UP" -> "Available";
            case "DOWN", "OUT_OF_SERVICE" -> "Service disruption";
            default -> "Status unavailable";
        });
        return "system/status";
    }

    @GetMapping("/")
    String overview(Authentication principal, Model model) {
        if (principal.getPrincipal() instanceof za.gov.govaccess.security.ManagedPrincipal user
                && user.role() == za.gov.govaccess.security.ManagedRole.APPLICANT) {
            return "auth/applicant-workspace";
        }
        model.addAttribute("username", principal.getName());
        boolean legacyPolicyAccess = ActorIdentity.has(principal, "PERM_POLICY_READ");
        boolean policyAccessAllowed = legacyPolicyAccess || ActorIdentity.has(principal, "POLICY_VIEW")
                || ActorIdentity.has(principal, "POLICY_EDIT");
        model.addAttribute("canReadPolicies", policyAccessAllowed);
        if (policyAccessAllowed) {
            var summary = legacyPolicyAccess ? policyAccess.summary() : new PolicyRecordAccessService.PolicySummary(
                    policies.count(), policies.countByStatus("DRAFT"), policies.countByStatus("PUBLISHED"));
            model.addAttribute("policyCount", summary.total());
            model.addAttribute("draftCount", summary.drafts());
            model.addAttribute("publishedCount", summary.published());
            model.addAttribute("policyVersions", legacyPolicyAccess ? policyAccess.recent()
                    : policies.findTop5ByOrderByVersionLabelDescIdAsc());
        }
        boolean caseAccess = ApplicationIntakeService.canReadCases(principal);
        model.addAttribute("canReadApplications", caseAccess);
        if (caseAccess) model.addAttribute("applicationDashboard", applicationIntake.dashboard(principal));
        return "home";
    }
}
