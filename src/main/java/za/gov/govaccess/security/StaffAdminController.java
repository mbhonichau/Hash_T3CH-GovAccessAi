package za.gov.govaccess.security;

import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@PreAuthorize("hasAuthority('PERM_USER_ADMIN')")
public class StaffAdminController {
    private final UserAdministrationService administration;

    public StaffAdminController(UserAdministrationService administration) {
        this.administration = administration;
    }

    @GetMapping("/admin/staff")
    public String staff(Model model) {
        model.addAttribute("username", "");
        model.addAttribute("accounts", administration.listAccounts());
        model.addAttribute("permissions", Permission.values());
        return "admin/staff";
    }

    @PostMapping("/admin/staff")
    public String create(@RequestParam String username, @RequestParam String password,
            @RequestParam(required = false) Set<Permission> permissions, RedirectAttributes redirect) {
        administration.createAccount(username, password, permissions == null ? Set.of() : permissions);
        redirect.addFlashAttribute("successMessage", "Staff account created.");
        return "redirect:/admin/staff";
    }

    @PostMapping("/admin/staff/permissions")
    public String updatePermissions(@RequestParam String username,
            @RequestParam(required = false) Set<Permission> permissions, RedirectAttributes redirect) {
        administration.replacePermissions(username, permissions == null ? Set.of() : permissions);
        redirect.addFlashAttribute("successMessage", "Permissions updated.");
        return "redirect:/admin/staff";
    }
}
