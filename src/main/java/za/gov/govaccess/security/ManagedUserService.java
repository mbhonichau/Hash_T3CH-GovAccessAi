package za.gov.govaccess.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;

@Service
public class ManagedUserService {
    public record Account(UUID id, String username, ManagedRole role, boolean enabled) {}

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final SecurityProperties security;
    private final BusinessAuditService audit;

    public ManagedUserService(JdbcTemplate jdbc, PasswordEncoder encoder, SecurityProperties security,
                              BusinessAuditService audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.security = security;
        this.audit = audit;
    }

    public Optional<ManagedPrincipal> principal(String username) {
        return jdbc.query("SELECT id,username,password_hash,role,enabled FROM govaccess.managed_user WHERE username=?",
                (rs, index) -> new ManagedPrincipal(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), ManagedRole.valueOf(rs.getString(4)), rs.getBoolean(5)),
                username.toLowerCase(Locale.ROOT)).stream().findFirst();
    }

    public List<Account> list(Authentication authentication) {
        ActorIdentity.require(authentication, "USER_MANAGE");
        return jdbc.query("""
                SELECT id,username,role,enabled FROM govaccess.managed_user ORDER BY username
                """, (rs, index) -> new Account(rs.getObject(1, UUID.class), rs.getString(2),
                ManagedRole.valueOf(rs.getString(3)), rs.getBoolean(4)));
    }

    @Transactional
    public UUID create(Authentication authentication, String username, String password, ManagedRole role) {
        ActorIdentity.require(authentication, "USER_MANAGE");
        String key = username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9][a-z0-9_.@-]{0,99}") || key.equalsIgnoreCase(security.getUser().getName())
                || role == null || password == null || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72 || principal(key).isPresent()) {
            throw new IllegalArgumentException("Choose a unique username, a role and a password of at least 12 characters and at most 72 bytes.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO govaccess.managed_user (id,username,password_hash,role,enabled) VALUES (?,?,?,?,true)",
                id, key, encoder.encode(password), role.name());
        var actor = ActorIdentity.of(authentication);
        audit.append(new Change(null, actor.id(), actor.role(), "USER_CREATED", null, null, null,
                null, Map.of("userId", id, "role", role.name()), null, null, null));
        return id;
    }

    @Transactional
    public void setEnabled(Authentication authentication, UUID userId, boolean enabled) {
        ActorIdentity.require(authentication, "USER_MANAGE");
        var actor = ActorIdentity.of(authentication);
        if (userId.equals(actor.id())) {
            throw new IllegalArgumentException("You cannot change your own account availability.");
        }
        var current = jdbc.query("SELECT enabled FROM govaccess.managed_user WHERE id=? FOR UPDATE",
                (rs, index) -> rs.getBoolean(1), userId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));
        if (current == enabled) {
            return;
        }
        jdbc.update("UPDATE govaccess.managed_user SET enabled=? WHERE id=?", enabled, userId);
        audit.append(new Change(null, actor.id(), actor.role(), "USER_AVAILABILITY_CHANGED", null, null, null,
                Map.of("userId", userId, "enabled", current), Map.of("userId", userId, "enabled", enabled),
                null, null, null));
    }

    public boolean isActive(ManagedPrincipal principal) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM govaccess.managed_user WHERE id=? AND enabled=true AND role=?)
                """, Boolean.class, principal.id(), principal.role().name()));
    }

    public String roleOf(UUID userId) {
        if (userId == null) {
            return "SYSTEM";
        }
        return jdbc.query("SELECT role FROM govaccess.managed_user WHERE id=?",
                (rs, index) -> rs.getString(1), userId).stream().findFirst().orElse("OPERATOR");
    }

    public long otherReviewers(UUID reviewerId) {
        long managed = jdbc.query("SELECT role FROM govaccess.managed_user WHERE enabled=true AND id<>?",
                (rs, index) -> ManagedRole.valueOf(rs.getString(1)), reviewerId).stream()
                .filter(role -> role.grants("POLICY_PUBLISH")).count();
        var bootstrap = security.getUser();
        return managed + (bootstrap.getRoles().contains("POLICY_PUBLISH")
                && !reviewerId.equals(ActorIdentity.of(bootstrap::getName).id()) ? 1 : 0);
    }
}
