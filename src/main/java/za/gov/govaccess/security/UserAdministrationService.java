package za.gov.govaccess.security;

import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account provisioning boundary for future staff administration screens and integrations. */
@Service
@Transactional
public class UserAdministrationService {
    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public UserAdministrationService(UserAccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    @PreAuthorize("hasAuthority('PERM_USER_ADMIN')")
    public List<StaffAccount> listAccounts() {
        return accounts.findAll().stream()
                .map(account -> new StaffAccount(account.getUsername(), account.isEnabled(), account.getPermissions()))
                .toList();
    }

    @PreAuthorize("hasAuthority('PERM_USER_ADMIN')")
    public void createAccount(String username, String rawPassword, Set<Permission> permissions) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.length() < 12) {
            throw new IllegalArgumentException("A username and password of at least 12 characters are required");
        }
        if (accounts.findByUsernameIgnoreCase(username).isPresent()) {
            throw new IllegalArgumentException("Username is already in use");
        }
        accounts.save(new UserAccount(username.strip(), passwordEncoder.encode(rawPassword), permissions));
    }

    @PreAuthorize("hasAuthority('PERM_USER_ADMIN')")
    public void replacePermissions(String username, Set<Permission> permissions) {
        var account = accounts.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("Account does not exist"));
        account.replacePermissions(permissions);
    }

    public record StaffAccount(String username, boolean enabled, Set<Permission> permissions) {}
}
