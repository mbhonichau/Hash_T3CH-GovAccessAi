package za.gov.govaccess.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
class WorkspaceAccessController {
    enum Mode { REVIEW, POLICY_EDIT }

    @GetMapping("/workspace/access")
    String show(Authentication authentication, HttpServletRequest request, Model model) {
        model.addAttribute("username", authentication.getName());
        model.addAttribute("workspaceAccessPage", true);
        model.addAttribute("canSwitchToEdit", PolicyWorkspaceAccess.mayEdit(authentication));
        model.addAttribute("editing", PolicyWorkspaceAccess.isEditing(authentication, request));
        return "auth/workspace-access";
    }

    @PostMapping("/workspace/access")
    String switchMode(@RequestParam Mode mode, Authentication authentication, HttpServletRequest request) {
        boolean editing = mode == Mode.POLICY_EDIT;
        if (editing && !PolicyWorkspaceAccess.mayEdit(authentication)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        PolicyWorkspaceAccess.switchMode(authentication, request, editing);
        return "redirect:/workspace/access";
    }
}
