package za.gov.govaccess.security;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "user_account")
public class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String passwordHash;
    private boolean enabled = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_account_permission", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    private Set<Permission> permissions = new HashSet<>();

    protected UserAccount() {}

    public UserAccount(String username, String passwordHash, Set<Permission> permissions) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.permissions = new HashSet<>(permissions);
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isEnabled() { return enabled; }
    public Set<Permission> getPermissions() { return Set.copyOf(permissions); }
    public void replacePermissions(Set<Permission> permissions) { this.permissions = new HashSet<>(permissions); }
}
