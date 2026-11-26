package za.gov.govaccess.security;

import com.hashtech.govaccessai.GovaccessaiApplication;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = GovaccessaiApplication.class, properties = {
        "spring.profiles.active=demo,test",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:demo-seeder;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class DemoDatabaseSeederTests {
    @Autowired UserAccountRepository accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired DemoDatabaseSeeder seeder;

    @Test
    void seedsDistinctBcryptAccountsAndDoesNotChangeExistingCredentials() {
        var expected = Map.of(
                "demo-admin", Set.of(Permission.values()),
                "policy-officer", Set.of(Permission.DASHBOARD_READ, Permission.POLICY_READ, Permission.POLICY_WRITE),
                "case-reviewer", Set.of(Permission.DASHBOARD_READ, Permission.APPLICATION_READ,
                        Permission.APPLICATION_CREATE, Permission.APPLICATION_REVIEW, Permission.EVIDENCE_READ),
                "read-only", Set.of(Permission.DASHBOARD_READ, Permission.POLICY_READ));

        assertThat(accounts.findAll()).hasSize(expected.size());
        expected.forEach((username, permissions) -> {
            var account = accounts.findByUsernameIgnoreCase(username).orElseThrow();
            assertThat(account.getPasswordHash()).startsWith("$2");
            assertThat(account.getPermissions()).containsExactlyInAnyOrderElementsOf(permissions);
        });

        Map<String, String> hashesBeforeRestart = accounts.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(UserAccount::getUsername, UserAccount::getPasswordHash));
        seeder.run(new DefaultApplicationArguments(new String[0]));
        assertThat(accounts.findAll()).hasSize(expected.size());
        assertThat(accounts.findAll()).allSatisfy(account ->
                assertThat(account.getPasswordHash()).isEqualTo(hashesBeforeRestart.get(account.getUsername())));
    }
}
