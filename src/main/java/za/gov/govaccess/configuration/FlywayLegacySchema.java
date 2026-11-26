package za.gov.govaccess.configuration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class FlywayLegacySchema {
    private static final String SCHEMA = "govaccess";
    private static final String FOUNDATION_STAGING = "govaccess_legacy_v2";
    private static final String STAGING = "govaccess_legacy_v3";
    private static final String AUDIT_STAGING = "govaccess_legacy_v5";
    private static final Map<String, InstalledMigration> EXTERNAL_HISTORY = Map.of(
            "5", new InstalledMigration("persistent user accounts", "V5__ai_request_audit.sql", 1555748309),
            "6", new InstalledMigration("application intake and workflow", "V6__application_intake_and_workflow.sql", 1487642100),
            "7", new InstalledMigration("synthetic intake cases", "V7__synthetic_intake_cases.sql", 2096334706));

    private record InstalledMigration(String description, String script, int checksum) {}

    @Bean
    FlywayMigrationStrategy migrateGovAccess() {
        return flyway -> {
            var jdbc = new JdbcTemplate(flyway.getConfiguration().getDataSource());
            if (!exists(jdbc, SCHEMA, "flyway_schema_history")
                    && exists(jdbc, SCHEMA, "ai_call_log") && exists(jdbc, SCHEMA, "ai_cache")) {
                Flyway.configure().configuration(flyway.getConfiguration()).baselineVersion("2").load().baseline();
            }
            var migrationRunner = exists(jdbc, SCHEMA, "flyway_schema_history")
                    ? allowVerifiedExternalHistory(flyway) : flyway;
            migrationRunner.migrate();
        };
    }

    private static Flyway allowVerifiedExternalHistory(Flyway flyway) {
        var missing = Arrays.stream(flyway.info().all())
                .filter(info -> info.getState() == MigrationState.MISSING_SUCCESS
                        || info.getState() == MigrationState.MISSING_FAILED)
                .toList();
        if (missing.isEmpty() || !missing.stream().allMatch(info -> {
            var expected = EXTERNAL_HISTORY.get(info.getVersion().getVersion());
            return info.getState() == MigrationState.MISSING_SUCCESS && expected != null
                    && expected.description().equals(info.getDescription()) && expected.script().equals(info.getScript())
                    && Objects.equals(expected.checksum(), info.getChecksum());
        })) {
            return flyway;
        }
        // Flyway's ignore pattern is coarse. Only enable it after checking every missing
        // entry against the exact identity of the externally installed migrations.
        return Flyway.configure().configuration(flyway.getConfiguration())
                .ignoreMigrationPatterns("versioned:missing").load();
    }

    @Bean
    Callback adoptLegacyPolicyTables() {
        return new Callback() {
            @Override
            public boolean supports(Event event, Context context) {
                return event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE;
            }

            @Override
            public boolean canHandleInTransaction(Event event, Context context) { return true; }

            @Override
            public String getCallbackName() { return "adopt Hibernate-created policy tables"; }

            @Override
            public void handle(Event event, Context context) {
                if (context.getMigrationInfo() == null || context.getMigrationInfo().getVersion() == null) {
                    return;
                }
                String version = context.getMigrationInfo().getVersion().getVersion();
                var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                        context.getConnection(), true));
                if (version.equals("2")) {
                    adoptFoundation(event, jdbc, context.getConnection());
                } else if (event == Event.BEFORE_EACH_MIGRATE && version.equals("3")) {
                    stage(jdbc, context.getConnection());
                } else if (event == Event.AFTER_EACH_MIGRATE && version.equals("4")
                        && exists(jdbc, STAGING, "policy_version")) {
                    restore(jdbc);
                } else if (version.equals("8")) {
                    adoptRequestLog(event, jdbc, context.getConnection());
                }
            }
        };
    }

    private static void adoptFoundation(Event event, JdbcTemplate jdbc, Connection connection) {
        boolean before = event == Event.BEFORE_EACH_MIGRATE;
        String source = before ? SCHEMA : FOUNDATION_STAGING;
        boolean calls = exists(jdbc, source, "ai_call_log");
        boolean cache = exists(jdbc, source, "ai_cache");
        if (!calls && !cache) {
            return;
        }
        if (before) {
            requirePostgres(connection);
            jdbc.execute("CREATE SCHEMA " + FOUNDATION_STAGING);
            if (calls) {
                jdbc.execute("ALTER TABLE govaccess.ai_call_log SET SCHEMA " + FOUNDATION_STAGING);
            }
            if (cache) {
                jdbc.execute("ALTER TABLE govaccess.ai_cache SET SCHEMA " + FOUNDATION_STAGING);
            }
            return;
        }
        if (calls) {
            jdbc.update("""
                    INSERT INTO govaccess.ai_call_log (
                        id, created_at, user_id, prompt_id, prompt_version, provider, model, input_sha256,
                        output_sha256, latency_ms, tokens_in, tokens_out, outcome, related_entity_type, related_entity_id
                    )
                    SELECT id, created_at, user_id, prompt_id, prompt_version, provider, model, input_sha256,
                           output_sha256, latency_ms, tokens_in, tokens_out, outcome, related_entity_type, related_entity_id
                    FROM govaccess_legacy_v2.ai_call_log
                    """);
            jdbc.execute("DROP TABLE govaccess_legacy_v2.ai_call_log");
        }
        if (cache) {
            jdbc.update("""
                    INSERT INTO govaccess.ai_cache (cache_key, raw_text, model, provider, tokens_in, tokens_out)
                    SELECT cache_key, raw_text, model, provider, tokens_in, tokens_out FROM govaccess_legacy_v2.ai_cache
                    """);
            jdbc.execute("DROP TABLE govaccess_legacy_v2.ai_cache");
        }
        jdbc.execute("DROP SCHEMA " + FOUNDATION_STAGING);
    }

    private static void stage(JdbcTemplate jdbc, Connection connection) {
        boolean programme = exists(jdbc, SCHEMA, "programme");
        boolean policyVersion = exists(jdbc, SCHEMA, "policy_version");
        if (!programme && !policyVersion) {
            return;
        }
        if (!programme || !policyVersion) {
            throw new IllegalStateException("The legacy programme and policy_version tables must both exist before adoption.");
        }
        requirePostgres(connection);
        jdbc.execute("CREATE SCHEMA " + STAGING);
        jdbc.execute("ALTER TABLE govaccess.policy_version SET SCHEMA " + STAGING);
        jdbc.execute("ALTER TABLE govaccess.programme SET SCHEMA " + STAGING);
    }

    private static void requirePostgres(Connection connection) {
        try {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                throw new IllegalStateException("Adoption of Hibernate-created tables requires PostgreSQL.");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot identify the database for legacy adoption.", e);
        }
    }

    private static void adoptRequestLog(Event event, JdbcTemplate jdbc, Connection connection) {
        if (event == Event.BEFORE_EACH_MIGRATE && exists(jdbc, SCHEMA, "ai_request_log")
                && !hasRequestLink(jdbc)) {
            requirePostgres(connection);
            jdbc.execute("CREATE SCHEMA " + AUDIT_STAGING);
            jdbc.execute("ALTER TABLE govaccess.ai_request_log SET SCHEMA " + AUDIT_STAGING);
        } else if (event == Event.AFTER_EACH_MIGRATE && exists(jdbc, AUDIT_STAGING, "ai_request_log")) {
            jdbc.update("""
                    INSERT INTO govaccess.ai_request_log (
                        id, created_at, user_id, related_entity_type, related_entity_id, prompt_id, prompt_version,
                        provider, model, outcome, attempt_count, total_latency_ms, input_sha256, output_sha256
                    )
                    SELECT id, created_at, user_id, related_entity_type, related_entity_id, prompt_id, prompt_version,
                           provider, model, outcome, attempt_count, total_latency_ms, input_sha256, output_sha256
                    FROM govaccess_legacy_v5.ai_request_log
                    """);
            jdbc.execute("DROP TABLE govaccess_legacy_v5.ai_request_log");
            jdbc.execute("DROP SCHEMA " + AUDIT_STAGING);
        }
    }

    private static boolean hasRequestLink(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE LOWER(table_schema) = 'govaccess' AND LOWER(table_name) = 'ai_call_log'
                AND LOWER(column_name) = 'request_id')
                """, Boolean.class));
    }

    private static void restore(JdbcTemplate jdbc) {
        // V4 inserted only the demo rows into the newly created tables. Restore the operator's
        // original rows, including any saved demo version, in the same transaction as V4.
        jdbc.update("DELETE FROM govaccess.policy_version WHERE id = '3c72c8f9-080f-49c4-9a96-36092d0d9187'");
        jdbc.update("DELETE FROM govaccess.programme WHERE id = 'e58c7032-b27d-4117-9885-63d830fd6453'");
        jdbc.update("""
                INSERT INTO govaccess.programme (id, code, name)
                SELECT id, code, name FROM govaccess_legacy_v3.programme
                """);
        jdbc.update("""
                INSERT INTO govaccess.policy_version (id, programme_id, version_label, status, origin, rule_json)
                SELECT id, programme_id, version_label, status, origin, rule_json FROM govaccess_legacy_v3.policy_version
                """);
        jdbc.execute("DROP TABLE govaccess_legacy_v3.policy_version");
        jdbc.execute("DROP TABLE govaccess_legacy_v3.programme");
        jdbc.execute("DROP SCHEMA " + STAGING);
    }

    private static boolean exists(JdbcTemplate jdbc, String schema, String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM information_schema.tables
                WHERE LOWER(table_schema) = ? AND LOWER(table_name) = ?)
                """, Boolean.class, schema, table));
    }
}
