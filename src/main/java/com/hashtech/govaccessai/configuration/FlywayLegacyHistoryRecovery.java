package com.hashtech.govaccessai.configuration;

import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.ValidateResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/** Baselines an existing Hibernate schema at its latest complete migration level before Flyway starts. */
@Component
public class FlywayLegacyHistoryRecovery implements FlywayConfigurationCustomizer, FlywayMigrationStrategy {
    private static final Set<String> AI_CALL_LOG_COLUMNS = Set.of(
            "id", "created_at", "prompt_id", "prompt_version", "input_sha256", "latency_ms", "outcome");
    private static final Set<String> AI_CACHE_COLUMNS = Set.of("cache_key", "raw_text", "model", "provider");
    private static final Set<String> PROGRAMME_COLUMNS = Set.of("id", "code", "name");
    private static final Set<String> POLICY_COLUMNS = Set.of(
            "id", "programme_id", "version_label", "status", "origin", "rule_json");
    private static final Set<String> USER_COLUMNS = Set.of("id", "username", "password_hash", "enabled");
    private static final Set<String> PERMISSION_COLUMNS = Set.of("user_id", "permissions");

    private final JdbcTemplate jdbc;
    private final String schema;

    public FlywayLegacyHistoryRecovery(DataSource dataSource,
            @Value("${spring.flyway.default-schema:public}") String schema) {
        this.jdbc = new JdbcTemplate(dataSource);
        if (schema == null || !schema.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Flyway default schema must be a simple SQL identifier");
        }
        this.schema = schema;
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        if (hasSuccessfulHistory()) return;
        int existingVersion = detectLegacyVersion();
        configuration.baselineOnMigrate(true).baselineVersion(MigrationVersion.fromVersion(Integer.toString(existingVersion)));
    }

    @Override
    public void migrate(Flyway flyway) {
        if (hasSuccessfulHistory()) {
            repairKnownVersionFiveChecksumDrift(flyway);
            flyway.migrate();
            return;
        }
        removeFailedHistoryIfSafe(detectLegacyVersion());
        flyway.migrate();
    }

    private void repairKnownVersionFiveChecksumDrift(Flyway flyway) {
        ValidateResult validation = flyway.validateWithResult();
        if (validation.validationSuccessful) return;

        Set<String> pendingVersions = Arrays.stream(flyway.info().pending())
                .map(migration -> migration.getVersion().getVersion())
                .collect(Collectors.toSet());
        var nonPendingIssues = validation.invalidMigrations.stream()
                .filter(invalid -> !pendingVersions.contains(invalid.version)).toList();
        if (nonPendingIssues.size() != 1) return;

        var invalid = nonPendingIssues.getFirst();
        if (!"5".equals(invalid.version)
                || invalid.errorDetails == null
                || invalid.errorDetails.errorMessage == null
                || !invalid.errorDetails.errorMessage.toLowerCase().contains("checksum mismatch")) {
            return;
        }

        String history = "\"" + schema + "\".\"flyway_schema_history\"";
        Integer appliedVersionFive = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + history + " WHERE version = '5' AND success = TRUE", Integer.class);
        if (appliedVersionFive == null || appliedVersionFive != 1
                || !tableExists("user_account") || !tableExists("user_account_permission")) {
            return;
        }

        requireColumns("user_account", USER_COLUMNS);
        requireColumns("user_account_permission", PERMISSION_COLUMNS);
        flyway.repair();
    }

    private boolean hasSuccessfulHistory() {
        if (!tableExists("flyway_schema_history")) return false;
        String history = "\"" + schema + "\".\"flyway_schema_history\"";
        Integer successfulMigrations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + history + " WHERE success = TRUE", Integer.class);
        return successfulMigrations != null && successfulMigrations > 0;
    }

    private int detectLegacyVersion() {
        boolean hasAiLog = tableExists("ai_call_log");
        boolean hasAiCache = tableExists("ai_cache");
        if (!hasAiLog && !hasAiCache) return 0;

        requireColumns("ai_call_log", AI_CALL_LOG_COLUMNS);
        if (!hasAiCache) {
            jdbc.execute("CREATE TABLE \"" + schema + "\".ai_cache ("
                    + "cache_key varchar(64) PRIMARY KEY, raw_text text NOT NULL, model varchar(255) NOT NULL, "
                    + "provider varchar(255) NOT NULL, tokens_in integer, tokens_out integer)");
        } else {
            requireColumns("ai_cache", AI_CACHE_COLUMNS);
        }

        int version = 2;
        boolean hasProgramme = tableExists("programme");
        boolean hasPolicy = tableExists("policy_version");
        if (hasProgramme != hasPolicy) {
            throw new IllegalStateException("Legacy schema has only one of programme and policy_version; "
                    + "refusing to replay a migration over existing data");
        }
        if (!hasProgramme) return version;
        requireColumns("programme", PROGRAMME_COLUMNS);
        requireColumns("policy_version", POLICY_COLUMNS);
        version = 3;

        Integer seededDrafts = jdbc.queryForObject("SELECT COUNT(*) FROM \"" + schema
                + "\".policy_version WHERE version_label = ?", Integer.class, "2025 financial screening");
        if (seededDrafts != null && seededDrafts > 0) version = 4;

        boolean hasUsers = tableExists("user_account");
        boolean hasPermissions = tableExists("user_account_permission");
        if (hasUsers != hasPermissions) {
            throw new IllegalStateException("Legacy schema has an incomplete staff account migration; "
                    + "refusing to replay a migration over existing data");
        }
        if (hasUsers) {
            requireColumns("user_account", USER_COLUMNS);
            requireColumns("user_account_permission", PERMISSION_COLUMNS);
            version = 5;
        }
        return version;
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_name = ? AND table_type = 'BASE TABLE'",
                Integer.class, schema, table);
        return count != null && count > 0;
    }

    private void requireColumns(String table, Set<String> required) {
        Set<String> actual = Set.copyOf(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ?", String.class, schema, table));
        if (!actual.containsAll(required)) {
            throw new IllegalStateException("Legacy table " + schema + "." + table
                    + " is missing columns required by the matching Flyway migration");
        }
    }

    private void removeFailedHistoryIfSafe(int existingVersion) {
        Integer historyTables = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_name = 'flyway_schema_history' AND table_type = 'BASE TABLE'",
                Integer.class, schema);
        if (historyTables == null || historyTables == 0 || existingVersion == 0) return;

        String history = "\"" + schema + "\".\"flyway_schema_history\"";
        Integer successfulMigrations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + history + " WHERE success = TRUE", Integer.class);
        if (successfulMigrations != null && successfulMigrations > 0) return;

        jdbc.execute("DROP TABLE " + history);
    }
}
