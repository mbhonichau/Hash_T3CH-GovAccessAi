package za.gov.govaccess.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates persistent, least-privilege demo identities without resetting existing credentials. */
@Component
@Profile("demo")
public class DemoDatabaseSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDatabaseSeeder.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Set<Permission>> DEMO_USERS = demoUsers();

    private final UserAccountRepository accounts;
    private final PasswordEncoder encoder;

    public DemoDatabaseSeeder(UserAccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        DEMO_USERS.forEach(this::createIfMissing);
    }

    private void createIfMissing(String username, Set<Permission> permissions) {
        if (accounts.findByUsernameIgnoreCase(username).isPresent()) return;

        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        accounts.saveAndFlush(new UserAccount(username, encoder.encode(password), permissions));
        log.warn("\n============================================================\nNEW DEMO LOGIN (save this password now)\nUsername: {}\nTemporary password: {}\nPermissions: {}\nThis password is shown only once.\n============================================================",
                username, password, permissions);
    }

    private static Map<String, Set<Permission>> demoUsers() {
        var users = new LinkedHashMap<String, Set<Permission>>();
        users.put("demo-admin", EnumSet.allOf(Permission.class));
        users.put("policy-officer", EnumSet.of(Permission.DASHBOARD_READ, Permission.POLICY_READ,
                Permission.POLICY_WRITE));
        users.put("case-reviewer", EnumSet.of(Permission.DASHBOARD_READ, Permission.APPLICATION_READ,
                Permission.APPLICATION_CREATE, Permission.APPLICATION_REVIEW, Permission.EVIDENCE_READ));
        users.put("read-only", EnumSet.of(Permission.DASHBOARD_READ, Permission.POLICY_READ));
        return Map.copyOf(users);
    }
}
