package za.gov.govaccess.security;

import java.util.UUID;
import org.springframework.security.core.userdetails.User;

public final class ManagedPrincipal extends User {
    private final UUID id;
    private final ManagedRole role;

    public ManagedPrincipal(UUID id, String username, String hash, ManagedRole role, boolean enabled) {
        super(username, hash, enabled, true, true, true, role.authorities());
        this.id = id;
        this.role = role;
    }

    public UUID id() { return id; }
    public ManagedRole role() { return role; }
}
