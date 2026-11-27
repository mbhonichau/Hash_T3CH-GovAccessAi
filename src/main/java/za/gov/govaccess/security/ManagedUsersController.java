package za.gov.govaccess.security;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import za.gov.govaccess.audit.service.AuditHistoryService;

@Controller
@RequestMapping("/admin/users")
class ManagedUsersController {
    private final ManagedUserService users;
    private final AuditHistoryService auditHistory;

    ManagedUsersController(ManagedUserService users, AuditHistoryService auditHistory) {
        this.users = users;
        this.auditHistory = auditHistory;
    }

    @GetMapping
    String index(Authentication actor, Model model) {
        model.addAttribute("username", actor.getName());
        model.addAttribute("managedUsersPage", true);
        model.addAttribute("accounts", users.list(actor));
        model.addAttribute("roles", ManagedRole.values());
        return "auth/managed-users";
    }

    @PostMapping
    String create(Authentication actor, @RequestParam String username, @RequestParam String password,
                  @RequestParam ManagedRole role, RedirectAttributes redirect) {
        return execute(redirect, "Account created.", () -> users.create(actor, username, password, role));
    }

    @PostMapping("/{id}/enabled")
    String enabled(Authentication actor, @PathVariable UUID id, @RequestParam boolean enabled,
                   RedirectAttributes redirect) {
        return execute(redirect, enabled ? "Account enabled." : "Account disabled.",
                () -> users.setEnabled(actor, id, enabled));
    }

    @PostMapping("/{id}/audit-scope")
    String scope(Authentication actor, @PathVariable UUID id, @RequestParam(required = false) UUID applicationId,
                 @RequestParam(required = false) UUID programmeId, RedirectAttributes redirect) {
        return execute(redirect, "Audit scope added.", () -> auditHistory.grantScope(actor, id, applicationId, programmeId));
    }

    private static String execute(RedirectAttributes redirect, String success, Runnable action) {
        try {
            action.run();
            redirect.addFlashAttribute("message", success);
        } catch (DataIntegrityViolationException e) {
            redirect.addFlashAttribute("error", "The account or scope could not be saved. Check the values for duplicates.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }
}
