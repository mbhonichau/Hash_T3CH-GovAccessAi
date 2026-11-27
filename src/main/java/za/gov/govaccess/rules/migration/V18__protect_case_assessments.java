package za.gov.govaccess.rules.migration;

import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V18__protect_case_assessments extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws SQLException {
        var connection = context.getConnection();
        try (var sql = connection.createStatement()) {
            if ("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                sql.execute("""
                        CREATE FUNCTION govaccess.guard_case_assessment() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF OLD.policy_hash IS NOT NULL OR EXISTS (
                                SELECT 1 FROM govaccess.assessment_evidence_quality WHERE assessment_id=OLD.id
                            ) THEN
                                RAISE EXCEPTION 'Case assessments are append-only' USING ERRCODE = '23514';
                            END IF;
                            IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
                            RETURN NEW;
                        END $$;
                        CREATE TRIGGER case_assessment_immutable BEFORE UPDATE OR DELETE ON govaccess.assessment
                            FOR EACH ROW EXECUTE FUNCTION govaccess.guard_case_assessment();
                        CREATE TRIGGER case_assessment_no_truncate BEFORE TRUNCATE ON govaccess.assessment
                            FOR EACH STATEMENT EXECUTE FUNCTION govaccess.guard_audit_append_only();
                        CREATE TRIGGER case_assessment_quality_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
                            ON govaccess.assessment_evidence_quality
                            FOR EACH STATEMENT EXECUTE FUNCTION govaccess.guard_audit_append_only()
                        """);
            } else {
                sql.execute("""
                        CREATE TRIGGER govaccess.case_assessment_immutable BEFORE UPDATE, DELETE ON govaccess.assessment
                        FOR EACH ROW AS '
                        org.h2.api.Trigger create() {
                            return (connection, oldRow, newRow) -> {
                                if (oldRow[8] != null) throw new java.sql.SQLException("Case assessments are append-only", "23514");
                                try (var query = connection.prepareStatement(
                                        "SELECT 1 FROM govaccess.assessment_evidence_quality WHERE assessment_id=?")) {
                                    query.setObject(1, oldRow[0]);
                                    try (var rows = query.executeQuery()) {
                                        if (rows.next()) throw new java.sql.SQLException("Case assessments are append-only", "23514");
                                    }
                                }
                            };
                        }'
                        """);
                sql.execute("""
                        CREATE TRIGGER govaccess.case_assessment_quality_immutable BEFORE UPDATE, DELETE
                        ON govaccess.assessment_evidence_quality FOR EACH ROW AS '
                        org.h2.api.Trigger create() {
                            return (connection, oldRow, newRow) -> {
                                throw new java.sql.SQLException("Case assessment quality records are append-only", "23514");
                            };
                        }'
                        """);
            }
        }
    }
}
