package za.gov.govaccess.audit.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import za.gov.govaccess.audit.service.AuditHistoryService;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.security.ActorIdentity;

@Controller
class BusinessAuditController {
    private final BusinessAuditService audit;
    private final AuditHistoryService history;

    BusinessAuditController(BusinessAuditService audit, AuditHistoryService history) {
        this.audit = audit;
        this.history = history;
    }

    @GetMapping("/audit")
    String chain(Authentication actor, Model model) {
        ActorIdentity.require(actor, "AUDIT_VERIFY");
        model.addAttribute("username", actor.getName());
        model.addAttribute("auditPage", true);
        model.addAttribute("verification", audit.verifyChain());
        return "audit/chain";
    }

    @GetMapping("/audit/history")
    String history(Authentication actor, Model model) {
        model.addAttribute("username", actor.getName());
        model.addAttribute("auditPage", true);
        model.addAttribute("events", history.visible(actor));
        model.addAttribute("canVerify", ActorIdentity.has(actor, "AUDIT_VERIFY"));
        return "audit/history";
    }
}
