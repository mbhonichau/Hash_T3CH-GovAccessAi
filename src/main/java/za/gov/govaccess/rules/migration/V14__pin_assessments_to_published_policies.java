package za.gov.govaccess.rules.migration;

import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V14__pin_assessments_to_published_policies extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws SQLException {
        var connection = context.getConnection();
        try (var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE govaccess.policy_version ADD COLUMN publisher_name varchar(255)");
            sql.execute("""
                    ALTER TABLE govaccess.policy_version ADD CONSTRAINT policy_version_snapshot_key UNIQUE (id, content_hash)
                    """);
            sql.execute("""
                    CREATE TABLE govaccess.assessment_policy_run (
                        id uuid PRIMARY KEY,
                        assessor_id uuid NOT NULL,
                        application_date date NOT NULL,
                        assessed_at timestamp with time zone NOT NULL,
                        policy_version_id uuid NOT NULL,
                        policy_hash varchar(64) NOT NULL,
                        recommendation varchar(32) NOT NULL
                            CHECK (recommendation IN ('NEEDS_REVIEW', 'NOT_ELIGIBLE', 'LIKELY_ELIGIBLE')),
                        FOREIGN KEY (policy_version_id, policy_hash) REFERENCES govaccess.policy_version(id, content_hash)
                    )
                    """);
            if ("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                sql.execute("""
                        CREATE TRIGGER policy_version_no_truncate BEFORE TRUNCATE ON govaccess.policy_version
                        FOR EACH STATEMENT EXECUTE FUNCTION govaccess.guard_audit_append_only()
                        """);
                sql.execute("""
                        CREATE TRIGGER assessment_policy_run_append_only BEFORE UPDATE OR DELETE OR TRUNCATE
                        ON govaccess.assessment_policy_run FOR EACH STATEMENT
                        EXECUTE FUNCTION govaccess.guard_audit_append_only()
                        """);
            } else {
                sql.execute("""
                        CREATE TRIGGER assessment_policy_run_append_only BEFORE UPDATE, DELETE
                        ON govaccess.assessment_policy_run FOR EACH ROW AS '
                        org.h2.api.Trigger create() {
                            return (connection, oldRow, newRow) -> {
                                throw new java.sql.SQLException("Assessment results are immutable", "23514");
                            };
                        }'
                        """);
            }
        }
    }
}
