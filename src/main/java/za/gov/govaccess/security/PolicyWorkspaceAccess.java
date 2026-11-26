package za.gov.govaccess.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

public final class PolicyWorkspaceAccess {
    private static final String EDITOR = PolicyWorkspaceAccess.class.getName() + ".editor";

    private PolicyWorkspaceAccess() {}

    public static boolean mayEdit(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(authority ->
                        "POLICY_EDIT".equals(authority.getAuthority())
                                || "ROLE_POLICY_EDIT".equals(authority.getAuthority()));
    }

    public static boolean isEditing(Authentication authentication, HttpServletRequest request) {
        var session = request.getSession(false);
        return mayEdit(authentication) && session != null
                && authentication.getName().equals(session.getAttribute(EDITOR));
    }

    static void switchMode(Authentication authentication, HttpServletRequest request, boolean editing) {
        var session = request.getSession(editing);
        if (editing) {
            session.setAttribute(EDITOR, authentication.getName());
        } else if (session != null) {
            session.removeAttribute(EDITOR);
        }
    }
}
