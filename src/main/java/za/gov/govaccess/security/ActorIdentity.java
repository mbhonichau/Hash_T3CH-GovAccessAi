package za.gov.govaccess.security;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

public record ActorIdentity(UUID id, String role) {
    public static ActorIdentity of(Principal principal) {
        if (principal == null) {
            return new ActorIdentity(null, "SYSTEM");
        }
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof ManagedPrincipal user) {
            return new ActorIdentity(user.id(), user.role().name());
        }
        String role = principal instanceof Authentication authentication
                ? authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted()
                        .filter(authority -> authority.startsWith("ROLE_")).findFirst().orElse("OPERATOR")
                : "OPERATOR";
        return new ActorIdentity(UUID.nameUUIDFromBytes(
                ("govaccess:principal:" + principal.getName()).getBytes(StandardCharsets.UTF_8)), role);
    }

    public static boolean has(Authentication authentication, String permission) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(value ->
                        permission.equals(value.getAuthority()) || ("ROLE_" + permission).equals(value.getAuthority()));
    }

    public static void require(Authentication authentication, String permission) {
        if (!has(authentication, permission)) {
            throw new org.springframework.security.access.AccessDeniedException("Permission required: " + permission);
        }
    }
}
