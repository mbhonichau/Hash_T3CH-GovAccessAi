package za.gov.govaccess.rules.migration;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V21__ai_governance extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws SQLException {
        var connection = context.getConnection();
        for (String table : List.of("ai_call_log", "ai_request_log")) {
            var constraints = new ArrayList<String>();
            try (var statement = connection.prepareStatement("""
                    SELECT tc.constraint_name FROM information_schema.table_constraints tc
                    JOIN information_schema.check_constraints cc ON tc.constraint_catalog=cc.constraint_catalog
                     AND tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name
                    WHERE LOWER(tc.table_schema)='govaccess' AND LOWER(tc.table_name)=? AND tc.constraint_type='CHECK'
                     AND UPPER(cc.check_clause) LIKE '%CACHE_HIT%' AND UPPER(cc.check_clause) LIKE '%KILL_SWITCH%'
                    """)) {
                statement.setString(1, table);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) constraints.add(rows.getString(1));
                }
            }
            try (var statement = connection.createStatement()) {
                for (String constraint : constraints) {
                    statement.execute("ALTER TABLE govaccess." + table + " DROP CONSTRAINT \""
                            + constraint.replace("\"", "\"\"") + "\"");
                }
                statement.execute("ALTER TABLE govaccess." + table + " ADD CHECK (outcome IN ('SUCCESS','RETRY_SUCCESS',"
                        + "'INVALID_OUTPUT','PROVIDER_ERROR','CACHE_HIT','BLOCKED_PII','KILL_SWITCH','RATE_LIMITED'))");
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE govaccess.ai_request_log ADD COLUMN provider_fallback boolean NOT NULL DEFAULT false");
            statement.execute("ALTER TABLE govaccess.policy_import_job ADD COLUMN instruction_like_content boolean NOT NULL DEFAULT false");
            statement.execute("ALTER TABLE govaccess.document_processing ADD COLUMN instruction_like_content boolean NOT NULL DEFAULT false");
            statement.execute("""
                    CREATE TABLE govaccess.ai_user_quota (
                        user_id uuid PRIMARY KEY, minute_window bigint NOT NULL, day_window bigint NOT NULL,
                        minute_count integer NOT NULL, day_count integer NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE govaccess.ai_demo_response (
                        id uuid PRIMARY KEY, request_hash varchar(64) NOT NULL, prompt_id varchar(255) NOT NULL,
                        provider varchar(255) NOT NULL, model varchar(255) NOT NULL, raw_text text NOT NULL,
                        created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("REVOKE ALL ON TABLE govaccess.ai_demo_response FROM PUBLIC");
        }
    }
}
