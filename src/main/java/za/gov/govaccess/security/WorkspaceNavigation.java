package za.gov.govaccess.security;

import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
class WorkspaceNavigation {
    private final za.gov.govaccess.ai.service.AiInputSafety aiSafety;

    WorkspaceNavigation(za.gov.govaccess.ai.service.AiInputSafety aiSafety) { this.aiSafety = aiSafety; }
    @ModelAttribute
    void navigation(Authentication actor, Model model) {
        boolean managed = actor != null && actor.getPrincipal() instanceof ManagedPrincipal;
        model.addAttribute("navPolicyWorkspace", !managed || ActorIdentity.has(actor, "POLICY_VIEW"));
        model.addAttribute("navLifecycle", ActorIdentity.has(actor, "POLICY_EDIT")
                || ActorIdentity.has(actor, "POLICY_PUBLISH") || ActorIdentity.has(actor, "POLICY_TEST"));
        model.addAttribute("navAudit", ActorIdentity.has(actor, "AUDIT_VIEW_LIMITED")
                || ActorIdentity.has(actor, "AUDIT_VIEW_ALL"));
        model.addAttribute("navUsers", ActorIdentity.has(actor, "USER_MANAGE"));
        model.addAttribute("navHostedAi", aiSafety.hostedActive());
        model.addAttribute("navAiGovernance", ActorIdentity.has(actor, "AUDIT_VERIFY"));
    }
}
