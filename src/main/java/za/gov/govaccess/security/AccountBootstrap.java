package za.gov.govaccess.security;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("!demo")
public class AccountBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AccountBootstrap.class);
    private final UserAccountRepository accounts;
    private final PasswordEncoder encoder;
    private final String username;
    private final String configuredPassword;

    public AccountBootstrap(UserAccountRepository accounts, PasswordEncoder encoder,
            @Value("${govaccess.bootstrap.username:admin}") String username,
            @Value("${govaccess.bootstrap.password:}") String configuredPassword) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.username = username;
        this.configuredPassword = configuredPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.count() != 0) return;
        if (username.isBlank()) {
            throw new IllegalStateException("GOVACCESS_BOOTSTRAP_USERNAME must not be blank when creating the first account");
        }
        String initialUsername = username.strip();
        String password = configuredPassword;
        if (password.isBlank()) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            accounts.saveAndFlush(new UserAccount(initialUsername, encoder.encode(password), EnumSet.allOf(Permission.class)));
            log.warn("\n============================================================\nINITIAL STAFF LOGIN (save this password now)\nUsername: {}\nTemporary password: {}\nThis password is shown only once.\n============================================================", initialUsername, password);
            return;
        }
        if (password.length() < 12) {
            throw new IllegalStateException("GOVACCESS_BOOTSTRAP_PASSWORD must contain at least 12 characters");
        }
        accounts.saveAndFlush(new UserAccount(initialUsername, encoder.encode(password), EnumSet.allOf(Permission.class)));
        log.info("Initial administrator account '{}' was created using GOVACCESS_BOOTSTRAP_PASSWORD.", initialUsername);
    }
}
