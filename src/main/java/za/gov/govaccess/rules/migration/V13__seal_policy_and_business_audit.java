package za.gov.govaccess.rules.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import za.gov.govaccess.audit.dto.BusinessAuditEvent;
import za.gov.govaccess.audit.service.CanonicalJson;

public class V13__seal_policy_and_business_audit extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws SQLException {
        var connection = context.getConnection();
        migratePolicyConstraints(connection);
        backfillAudit(connection);
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE audit_event ALTER COLUMN event_sequence SET NOT NULL");
            statement.execute("ALTER TABLE audit_event ALTER COLUMN actor_role SET NOT NULL");
            statement.execute("ALTER TABLE audit_event ALTER COLUMN event_hash SET NOT NULL");
            statement.execute("ALTER TABLE audit_event ADD CONSTRAINT audit_event_no_inline_values CHECK (actor_username = '' AND detail = '')");
        }
        String database = connection.getMetaData().getDatabaseProductName();
        if ("PostgreSQL".equals(database)) {
            postgresGuards(connection);
        } else if ("H2".equals(database)) {
            h2Guards(connection);
        } else {
            throw new IllegalStateException("Policy and audit guards require PostgreSQL or H2");
        }
    }

    private static void migratePolicyConstraints(Connection connection) throws SQLException {
        var constraints = new ArrayList<String>();
        try (var statement = connection.prepareStatement("""
                SELECT tc.constraint_name FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc ON tc.constraint_catalog=cc.constraint_catalog
                    AND tc.constraint_schema=cc.constraint_schema AND tc.constraint_name=cc.constraint_name
                WHERE LOWER(tc.table_schema)='govaccess' AND LOWER(tc.table_name)='policy_version'
                  AND tc.constraint_type='CHECK' AND LOWER(cc.check_clause) LIKE '%status%'
                  AND LOWER(cc.check_clause) LIKE '%draft%' AND LOWER(cc.check_clause) LIKE '%published%'
                """); var rows = statement.executeQuery()) {
            while (rows.next()) {
                constraints.add(rows.getString(1));
            }
        }
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                    SELECT author_id,publisher_id,published_at,effective_from,content,content_hash
                    FROM policy_version WHERE status='PUBLISHED'
                    """)) {
            while (rows.next()) {
                String canonical = CanonicalJson.document(rows.getString("content"));
                if (id(rows, "author_id") == null || id(rows, "publisher_id") == null
                        || rows.getObject("published_at") == null || rows.getObject("effective_from") == null
                        || !CanonicalJson.sha256(canonical).equals(rows.getString("content_hash"))) {
                    throw new IllegalStateException("Legacy published policies must be reconciled with their publication metadata before upgrading");
                }
            }
        }
        try (var statement = connection.createStatement()) {
            for (String constraint : constraints) {
                statement.execute("ALTER TABLE policy_version DROP CONSTRAINT \"" + constraint.replace("\"", "\"\"") + "\"");
            }
            statement.execute("""
                    ALTER TABLE policy_version ADD CONSTRAINT policy_version_lifecycle_check
                    CHECK (status IN ('DRAFT','REVIEW','TESTED','PUBLISHED'))
                    """);
            statement.execute("""
                    ALTER TABLE policy_version ADD CONSTRAINT policy_version_publication_check
                    CHECK (status <> 'PUBLISHED' OR
                    (author_id IS NOT NULL AND publisher_id IS NOT NULL AND published_at IS NOT NULL
                    AND effective_from IS NOT NULL AND content_hash IS NOT NULL AND LENGTH(content_hash)=64))
                    """);
        }
    }

    private record Legacy(UUID id, UUID applicationId, UUID actorId, String actorRole, String action,
                          Instant time, UUID policyId, String policyHash, UUID snapshot,
                          String promptId, Integer promptVersion, String model, UUID requestId) {}

    private static void backfillAudit(Connection connection) throws SQLException {
        List<Legacy> history = new ArrayList<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id,application_id,actor_username,action,detail,occurred_at FROM audit_event")) {
            while (rows.next()) {
                String username = rows.getString("actor_username");
                UUID actor = username.isBlank() ? null : UUID.nameUUIDFromBytes(
                        ("govaccess:principal:" + username).getBytes(StandardCharsets.UTF_8));
                UUID snapshot = UUID.randomUUID();
                try (var insert = connection.prepareStatement("INSERT INTO audit_protected_snapshot VALUES (?,?)")) {
                    insert.setObject(1, snapshot);
                    insert.setString(2, CanonicalJson.write(Map.of("actorUsername", username, "detail", rows.getString("detail"))));
                    insert.executeUpdate();
                }
                history.add(new Legacy(id(rows, "id"), id(rows, "application_id"), actor, "LEGACY",
                        rows.getString("action"), time(rows, "occurred_at"), null, null, snapshot, null, null, null, null));
            }
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT r.id,r.created_at,r.user_id,r.related_entity_id,r.related_entity_type,
                       r.prompt_id,r.prompt_version,r.model,p.content
                FROM ai_request_log r LEFT JOIN policy_version p
                  ON r.related_entity_type='POLICY_VERSION' AND r.related_entity_id=p.id
                WHERE r.audit_event_id IS NULL
                """)) {
            while (rows.next()) {
                String content = rows.getString("content");
                UUID actor = id(rows, "user_id");
                history.add(new Legacy(UUID.randomUUID(), null, actor, actor == null ? "SYSTEM" : "LEGACY",
                        "AI_REQUEST_COMPLETED", time(rows, "created_at"),
                        "POLICY_VERSION".equals(rows.getString("related_entity_type")) ? id(rows, "related_entity_id") : null,
                        content == null ? null : CanonicalJson.sha256(CanonicalJson.document(content)), null,
                        rows.getString("prompt_id"), rows.getInt("prompt_version"), rows.getString("model"), id(rows, "id")));
            }
        }
        history.sort(Comparator.comparing(Legacy::time).thenComparing(Legacy::id));
        long sequence = 0;
        String previous = null;
        for (var legacy : history) {
            var event = new BusinessAuditEvent(legacy.id(), ++sequence, legacy.applicationId(), legacy.actorId(),
                    legacy.actorRole(), legacy.action(), legacy.time(), legacy.policyId(), legacy.policyHash(), null,
                    null, legacy.snapshot(), legacy.promptId(), legacy.promptVersion(), legacy.model(), previous, null);
            String hash = event.calculateHash();
            if (legacy.requestId() == null) {
                try (var statement = connection.prepareStatement("""
                        UPDATE audit_event SET actor_username='',detail='',event_sequence=?,actor_id=?,actor_role=?,
                        new_snapshot_id=?,previous_event_hash=?,event_hash=? WHERE id=?
                        """)) {
                    statement.setLong(1, sequence);
                    statement.setObject(2, legacy.actorId());
                    statement.setString(3, legacy.actorRole());
                    statement.setObject(4, legacy.snapshot());
                    statement.setString(5, previous);
                    statement.setString(6, hash);
                    statement.setObject(7, legacy.id());
                    statement.executeUpdate();
                }
            } else {
                try (var statement = connection.prepareStatement("""
                        INSERT INTO audit_event (id,actor_username,action,detail,occurred_at,event_sequence,actor_id,actor_role,
                        policy_version_id,policy_hash,ai_prompt_id,ai_prompt_version,ai_model,previous_event_hash,event_hash)
                        VALUES (?,'',?,'',?,?,?,?,?,?,?,?,?,?,?)
                        """)) {
                    statement.setObject(1, legacy.id());
                    statement.setString(2, legacy.action());
                    statement.setObject(3, OffsetDateTime.ofInstant(legacy.time(), ZoneOffset.UTC));
                    statement.setLong(4, sequence);
                    statement.setObject(5, legacy.actorId());
                    statement.setString(6, legacy.actorRole());
                    statement.setObject(7, legacy.policyId());
                    statement.setString(8, legacy.policyHash());
                    statement.setString(9, legacy.promptId());
                    statement.setObject(10, legacy.promptVersion());
                    statement.setString(11, legacy.model());
                    statement.setString(12, previous);
                    statement.setString(13, hash);
                    statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("UPDATE ai_request_log SET audit_event_id=? WHERE id=?")) {
                    statement.setObject(1, legacy.id());
                    statement.setObject(2, legacy.requestId());
                    statement.executeUpdate();
                }
            }
            previous = hash;
        }
        try (var statement = connection.prepareStatement("UPDATE audit_chain_head SET last_sequence=?,last_hash=? WHERE id=1")) {
            statement.setLong(1, sequence);
            statement.setString(2, previous);
            statement.executeUpdate();
        }
    }

    private static UUID id(ResultSet rows, String column) throws SQLException {
        return rows.getObject(column, UUID.class);
    }

    private static Instant time(ResultSet rows, String column) throws SQLException {
        return rows.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static void postgresGuards(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION govaccess.guard_published_policy() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        IF TG_OP = 'INSERT' THEN
                            IF NEW.status <> 'DRAFT' THEN
                                RAISE EXCEPTION 'New policy versions must start as drafts' USING ERRCODE = '23514';
                            END IF;
                            RETURN NEW;
                        END IF;
                        IF OLD.status = 'PUBLISHED' THEN
                            RAISE EXCEPTION 'Published policy versions are immutable' USING ERRCODE = '23514';
                        END IF;
                        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
                        IF OLD.status IS DISTINCT FROM NEW.status AND NOT (
                            (OLD.status = 'DRAFT' AND NEW.status = 'REVIEW') OR
                            (OLD.status = 'REVIEW' AND NEW.status = 'TESTED') OR
                            (OLD.status = 'TESTED' AND NEW.status = 'PUBLISHED')) THEN
                            RAISE EXCEPTION 'Invalid policy lifecycle transition' USING ERRCODE = '23514';
                        END IF;
                        RETURN NEW;
                    END $$;
                    """);
            statement.execute("""
                    CREATE TRIGGER policy_version_immutable BEFORE INSERT OR UPDATE OR DELETE ON govaccess.policy_version
                    FOR EACH ROW EXECUTE FUNCTION govaccess.guard_published_policy()
                    """);
            statement.execute("""
                    CREATE FUNCTION govaccess.guard_audit_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'Business audit records are append-only' USING ERRCODE = '23514';
                    END $$;
                    """);
            for (String table : List.of("audit_event", "audit_protected_snapshot", "policy_test_run")) {
                statement.execute("CREATE TRIGGER " + table + "_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON govaccess."
                        + table + " FOR EACH STATEMENT EXECUTE FUNCTION govaccess.guard_audit_append_only()");
            }
        }
    }

    private static void h2Guards(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER policy_version_immutable BEFORE INSERT, UPDATE, DELETE ON policy_version FOR EACH ROW AS '
                    org.h2.api.Trigger create() {
                        return new org.h2.api.Trigger() {
                            public void fire(java.sql.Connection c, Object[] oldRow, Object[] newRow) throws java.sql.SQLException {
                                if (oldRow == null) {
                                    if (!"DRAFT".equals(newRow[3]))
                                        throw new java.sql.SQLException("New policy versions must start as drafts", "23514");
                                    return;
                                }
                                String before = (String) oldRow[3];
                                if ("PUBLISHED".equals(before)) throw new java.sql.SQLException("Published policy versions are immutable", "23514");
                                if (newRow == null) return;
                                String after = (String) newRow[3];
                                if (!before.equals(after) && !(before.equals("DRAFT") && after.equals("REVIEW")
                                     || before.equals("REVIEW") && after.equals("TESTED")
                                     || before.equals("TESTED") && after.equals("PUBLISHED")))
                                    throw new java.sql.SQLException("Invalid policy lifecycle transition", "23514");
                            }
                        };
                    }'
                    """);
            for (String table : List.of("audit_event", "audit_protected_snapshot", "policy_test_run")) {
                statement.execute("CREATE TRIGGER " + table + "_append_only BEFORE UPDATE, DELETE ON " + table
                        + """
                         FOR EACH ROW AS '
                        org.h2.api.Trigger create() {
                            return new org.h2.api.Trigger() {
                                public void fire(java.sql.Connection c, Object[] oldRow, Object[] newRow) throws java.sql.SQLException {
                                    throw new java.sql.SQLException("Business audit records are append-only", "23514");
                                }
                            };
                        }'
                        """);
            }
        }
    }
}
