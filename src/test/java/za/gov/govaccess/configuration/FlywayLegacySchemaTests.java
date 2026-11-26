package za.gov.govaccess.configuration;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayLegacySchemaTests {
    @Test
    void upgradesAnExistingPreFlywayAuditSchemaAndPreservesItsRows() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:legacy-audit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var settings = Flyway.configure().dataSource(dataSource).defaultSchema("govaccess");
        settings.target("2").load().migrate();

        var jdbc = new JdbcTemplate(dataSource);
        var attemptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO govaccess.ai_call_log (id, created_at, prompt_id, prompt_version, input_sha256,
                    latency_ms, outcome)
                VALUES (?, CURRENT_TIMESTAMP, 'legacy-test', 1, ?, 5, 'SUCCESS')
                """, attemptId, "0".repeat(64));
        jdbc.execute("DROP TABLE govaccess.flyway_schema_history");

        var adoption = new FlywayLegacySchema();
        var current = Flyway.configure().dataSource(dataSource).defaultSchema("govaccess")
                .locations("classpath:db/migration", "classpath:za/gov/govaccess/rules/migration")
                .callbacks(adoption.adoptLegacyPolicyTables()).load();
        adoption.migrateGovAccess().migrate(current);
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(jdbc.queryForObject("SELECT request_id FROM govaccess.ai_call_log WHERE id = ?", UUID.class, attemptId))
                .isEqualTo(attemptId);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM govaccess.ai_request_log WHERE id = ?", Integer.class, attemptId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.policy_version", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content FROM govaccess.policy_version", String.class))
                .contains("\"schemaVersion\":1", "DEMO DATA ONLY");
        adoption.migrateGovAccess().migrate(current);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.ai_request_log", Integer.class)).isEqualTo(1);
    }

    @Test
    void preservesExternalIntakeHistoryAndAppliesTheNewMigrationsAfterIt() {
        var settings = externalHistoryFixture("known-intake");
        var adoption = new FlywayLegacySchema();
        var flyway = settings.load();
        adoption.migrateGovAccess().migrate(flyway);
        var jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(jdbc.queryForObject("SELECT checksum FROM govaccess.flyway_schema_history WHERE version = '6'",
                Integer.class)).isEqualTo(1487642100);
        adoption.migrateGovAccess().migrate(flyway);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.flyway_schema_history WHERE version = '10'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsUnknownOrAlteredExternalHistory() {
        var flyway = externalHistoryFixture("unknown-intake").load();
        var jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
        jdbc.update("UPDATE govaccess.flyway_schema_history SET checksum = 42 WHERE version = '6'");
        assertThatThrownBy(() -> new FlywayLegacySchema().migrateGovAccess().migrate(flyway))
                .isInstanceOf(org.flywaydb.core.api.exception.FlywayValidateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.flyway_schema_history WHERE version = '8'",
                Integer.class)).isZero();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration externalHistoryFixture(String database) {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + database
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var settings = Flyway.configure().dataSource(dataSource).defaultSchema("govaccess")
                .locations("classpath:db/migration", "classpath:za/gov/govaccess/rules/migration");
        settings.target("4").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        addHistory(jdbc, 50, "5", "persistent user accounts", "V5__ai_request_audit.sql", 1555748309);
        addHistory(jdbc, 51, "6", "application intake and workflow", "V6__application_intake_and_workflow.sql", 1487642100);
        addHistory(jdbc, 52, "7", "synthetic intake cases", "V7__synthetic_intake_cases.sql", 2096334706);
        return settings.target("10");
    }

    private static void addHistory(JdbcTemplate jdbc, int rank, String version, String description, String script,
                                   int checksum) {
        jdbc.update("""
                INSERT INTO govaccess.flyway_schema_history (installed_rank, version, description, type, script,
                    checksum, installed_by, execution_time, success)
                VALUES (?, ?, ?, 'SQL', ?, ?, 'fixture', 0, true)
                """, rank, version, description, script, checksum);
    }

    @Test
    void validatesTheResolvedSupabaseDatasourcePassword() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("supabase");
        var credentials = new SupabaseCredentials();
        environment.setProperty("spring.datasource.password", "${DB_PASSWORD:${SUPABASE_DB_PASSWORD:}}");
        assertThatThrownBy(() -> credentials.postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Supabase datasource password is missing");
        environment.setProperty("SUPABASE_DB_PASSWORD", "alias-fixture");
        credentials.postProcessEnvironment(environment, null);
        environment.setProperty("DB_PASSWORD", "fixture");
        credentials.postProcessEnvironment(environment, null);
        environment.setProperty("spring.datasource.password", "profile-fixture");
        credentials.postProcessEnvironment(environment, null);
    }
}
