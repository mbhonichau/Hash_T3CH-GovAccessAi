package za.gov.govaccess.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class PersistentUserDetailsService implements UserDetailsService {
    private final UserAccountRepository accounts;

    public PersistentUserDetailsService(UserAccountRepository accounts) { this.accounts = accounts; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        var account = accounts.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
        var authorities = account.getPermissions().stream()
                .map(permission -> new SimpleGrantedAuthority("PERM_" + permission.name())).toList();
        return User.withUsername(account.getUsername()).password(account.getPasswordHash())
                .disabled(!account.isEnabled()).authorities(authorities).build();
    }
}
