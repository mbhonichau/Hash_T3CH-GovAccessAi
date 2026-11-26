package za.gov.govaccess.application;

import java.util.UUID;
import com.hashtech.govaccessai.configuration.FlywayLegacyHistoryRecovery;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayUpgradeTests {
    @Test
    void recoversWhenFailedLegacyStartupLeftAnEmptyFlywayHistoryTable() {
        String url = "jdbc:h2:mem:flyway-empty-history;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(dataSource).schemas("govaccess").defaultSchema("govaccess")
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("5")).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM govaccess.flyway_schema_history");

        var recoveryStrategy = new FlywayLegacyHistoryRecovery(dataSource, "govaccess");
        FluentConfiguration recoveryConfig = Flyway.configure().dataSource(dataSource).schemas("govaccess")
                .defaultSchema("govaccess").locations("classpath:db/migration");
        recoveryStrategy.customize(recoveryConfig);
        recoveryStrategy.migrate(recoveryConfig.load());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.flyway_schema_history "
                + "WHERE version = '2' AND success = TRUE", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.case_application", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.ai_call_log", Integer.class)).isZero();
    }

    @Test
    void resumesLegacySchemaAtItsLatestExistingVersionAndCreatesLaterTables() {
        String url = "jdbc:h2:mem:flyway-v4-legacy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(dataSource).schemas("govaccess").defaultSchema("govaccess")
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("4")).load().migrate();

        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE govaccess.flyway_schema_history");

        var recoveryStrategy = new FlywayLegacyHistoryRecovery(dataSource, "govaccess");
        FluentConfiguration recoveryConfig = Flyway.configure().dataSource(dataSource).schemas("govaccess")
                .defaultSchema("govaccess").locations("classpath:db/migration");
        recoveryStrategy.customize(recoveryConfig);
        recoveryStrategy.migrate(recoveryConfig.load());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.flyway_schema_history "
                + "WHERE version = '4' AND type = 'BASELINE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.policy_version "
                + "WHERE version_label = '2025 financial screening'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.user_account", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.case_application", Integer.class)).isEqualTo(3);
    }

    @Test
    void avoidsReplayingAiFoundationWhenOnlyExistingAiLogTableIsPresent() {
        String url = "jdbc:h2:mem:flyway-ai-log-only;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA govaccess");
        jdbc.execute("CREATE TABLE govaccess.ai_call_log (id uuid PRIMARY KEY, created_at timestamp with time zone "
                + "NOT NULL, prompt_id varchar(255) NOT NULL, prompt_version integer NOT NULL, "
                + "input_sha256 varchar(64) NOT NULL, latency_ms bigint NOT NULL, outcome varchar(32) NOT NULL)");
        jdbc.update("INSERT INTO govaccess.ai_call_log (id, created_at, prompt_id, prompt_version, input_sha256, "
                + "latency_ms, outcome) VALUES (?, CURRENT_TIMESTAMP, 'legacy', 1, ?, 1, 'SUCCESS')",
                UUID.fromString("572c0206-5394-4c55-8c89-3df2f36915f1"), "b".repeat(64));

        var recoveryStrategy = new FlywayLegacyHistoryRecovery(dataSource, "govaccess");
        FluentConfiguration recoveryConfig = Flyway.configure().dataSource(dataSource).schemas("govaccess")
                .defaultSchema("govaccess").locations("classpath:db/migration");
        recoveryStrategy.customize(recoveryConfig);
        recoveryStrategy.migrate(recoveryConfig.load());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.flyway_schema_history "
                + "WHERE version = '2' AND type = 'BASELINE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.ai_call_log", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.case_application", Integer.class)).isEqualTo(3);
    }

    @Test
    void repairsOnlyTheKnownV5ChecksumWhenItsSchemaIsAlreadyPresent() {
        String url = "jdbc:h2:mem:flyway-v5-checksum;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(dataSource).schemas("govaccess").defaultSchema("govaccess")
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("5")).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE govaccess.flyway_schema_history SET checksum = ? WHERE version = '5'", 916768963);

        var recovery = new FlywayLegacyHistoryRecovery(dataSource, "govaccess");
        FluentConfiguration configuration = Flyway.configure().dataSource(dataSource).schemas("govaccess")
                .defaultSchema("govaccess").locations("classpath:db/migration");
        recovery.customize(configuration);
        recovery.migrate(configuration.load());

        assertThat(jdbc.queryForObject("SELECT checksum FROM govaccess.flyway_schema_history "
                + "WHERE version = '5'", Integer.class)).isNotEqualTo(916768963);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.user_account", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.case_application", Integer.class)).isEqualTo(3);
    }

    @Test
    void doesNotRepairV5ChecksumWhenExpectedAccountSchemaIsMissing() {
        String url = "jdbc:h2:mem:flyway-v5-checksum-invalid;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(dataSource).schemas("govaccess").defaultSchema("govaccess")
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("5")).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update("UPDATE govaccess.flyway_schema_history SET checksum = ? WHERE version = '5'", 916768963);
        jdbc.execute("DROP TABLE govaccess.user_account_permission");

        var recovery = new FlywayLegacyHistoryRecovery(dataSource, "govaccess");
        FluentConfiguration configuration = Flyway.configure().dataSource(dataSource).schemas("govaccess")
                .defaultSchema("govaccess").locations("classpath:db/migration");
        recovery.customize(configuration);

        assertThatThrownBy(() -> recovery.migrate(configuration.load()))
                .hasMessageContaining("Validate failed").hasMessageContaining("checksum mismatch");
        assertThat(jdbc.queryForObject("SELECT checksum FROM govaccess.flyway_schema_history "
                + "WHERE version = '5'", Integer.class)).isEqualTo(916768963);
    }

    @Test
    void freshAndLegacySchemasMigrateWithoutReplacingNsfAsDrafts() {
        String url = "jdbc:h2:mem:flyway-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        var dataSource = new DriverManagerDataSource(url, "sa", "");

        Flyway.configure().dataSource(dataSource).schemas("govaccess").defaultSchema("govaccess")
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("5")).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.policy_version WHERE version_label = ?",
                Integer.class, "2025 financial screening")).isEqualTo(1);

        UUID existingAiLogId = UUID.fromString("2a72c8f9-080f-49c4-9a96-36092d0d9187");
        jdbc.update("INSERT INTO govaccess.ai_call_log "
                        + "(id, created_at, prompt_id, prompt_version, input_sha256, latency_ms, outcome) "
                        + "VALUES (?, CURRENT_TIMESTAMP, 'legacy-prompt', 1, ?, 10, 'SUCCESS')",
                existingAiLogId, "a".repeat(64));

        UUID preservedDraftId = UUID.fromString("3d72c8f9-080f-49c4-9a96-36092d0d9187");
        jdbc.update("INSERT INTO govaccess.policy_version (id, programme_id, version_label, status, origin, rule_json) "
                        + "SELECT ?, id, ?, 'DRAFT', 'MANUAL', '{}' FROM govaccess.programme WHERE code = ?",
                preservedDraftId, "Existing reviewer draft", "NSFAS_BURSARY");

        // Simulate an existing Hibernate-managed database with data but no Flyway history table.
        jdbc.execute("DROP TABLE govaccess.flyway_schema_history");
        Flyway.configure().dataSource(dataSource).schemas("govaccess").defaultSchema("govaccess")
                .locations("classpath:db/migration").baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("5")).load().migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.policy_version WHERE status = 'DRAFT'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.policy_version WHERE id = ?",
                Integer.class, preservedDraftId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.ai_call_log WHERE id = ?",
                Integer.class, existingAiLogId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.flyway_schema_history "
                        + "WHERE version = '2' AND success = TRUE", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.flyway_schema_history "
                        + "WHERE version = '5' AND type = 'BASELINE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.case_application", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.audit_event", Integer.class)).isEqualTo(3);
    }
}
