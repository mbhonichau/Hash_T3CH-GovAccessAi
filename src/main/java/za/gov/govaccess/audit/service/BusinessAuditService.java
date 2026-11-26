package za.gov.govaccess.audit.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.audit.dto.BusinessAuditEvent;

@Service
public class BusinessAuditService {
    private final JdbcTemplate jdbc;

    public BusinessAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Change(UUID applicationId, UUID actorId, String actorRole, String action, UUID policyVersionId,
                         String policyHash, UUID assessmentId, Object previous, Object current,
                         String promptId, Integer promptVersion, String model) {}

    public record ChainVerification(boolean intact, UUID firstBrokenEvent, long sequence) {}
    private record Head(long sequence, String hash) {}

    @Transactional
    public UUID append(Change change) {
        var head = head(true);
        var timestamp = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var event = new BusinessAuditEvent(UUID.randomUUID(), head.sequence() + 1, change.applicationId(),
                change.actorId(), Objects.requireNonNull(change.actorRole()), change.action(), timestamp,
                change.policyVersionId(), change.policyHash(), change.assessmentId(),
                snapshot(change.previous()), snapshot(change.current()), change.promptId(),
                change.promptVersion(), change.model(), head.hash(), null);
        var hash = event.calculateHash();
        jdbc.update("""
                INSERT INTO govaccess.audit_event
                (id, application_id, actor_username, action, detail, occurred_at, event_sequence, actor_id, actor_role,
                 policy_version_id, policy_hash, assessment_id, previous_snapshot_id, new_snapshot_id, ai_prompt_id,
                 ai_prompt_version, ai_model, previous_event_hash, event_hash)
                VALUES (?, ?, '', ?, '', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.id(), event.applicationId(), event.action(), OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC),
                event.sequence(), event.actorId(), event.actorRole(), event.policyVersionId(), event.policyHash(),
                event.assessmentId(), event.previousSnapshotId(), event.newSnapshotId(),
                event.aiPromptId(), event.aiPromptVersion(), event.aiModel(), event.previousEventHash(), hash);
        jdbc.update("UPDATE govaccess.audit_chain_head SET last_sequence=?,last_hash=? WHERE id=1", event.sequence(), hash);
        return event.id();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ChainVerification verifyChain() {
        var head = head(false);
        try (var events = jdbc.queryForStream("SELECT * FROM govaccess.audit_event ORDER BY event_sequence",
                BusinessAuditService::read)) {
            var cursor = events.iterator();
            String previous = null;
            long sequence = 0;
            while (cursor.hasNext()) {
                var event = cursor.next();
                sequence++;
                if (event.sequence() != sequence || !Objects.equals(previous, event.previousEventHash())
                        || !Objects.equals(event.calculateHash(), event.eventHash())) {
                    return new ChainVerification(false, event.id(), sequence);
                }
                previous = event.eventHash();
            }
            return head.sequence() == sequence && Objects.equals(head.hash(), previous)
                    ? new ChainVerification(true, null, sequence)
                    : new ChainVerification(false, null, sequence + 1);
        }
    }

    private Head head(boolean lock) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT last_sequence,last_hash FROM govaccess.audit_chain_head WHERE id=1" + (lock ? " FOR UPDATE" : ""),
                (rs, index) -> new Head(rs.getLong(1), rs.getString(2))));
    }

    private UUID snapshot(Object value) {
        if (value == null) {
            return null;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO govaccess.audit_protected_snapshot (id,payload) VALUES (?,?)",
                id, CanonicalJson.write(value));
        return id;
    }

    @Transactional
    public UUID storeProtectedSnapshot(Object value) {
        return snapshot(Objects.requireNonNull(value));
    }

    private static BusinessAuditEvent read(ResultSet rs, int index) throws SQLException {
        return new BusinessAuditEvent(id(rs, "id"), rs.getLong("event_sequence"), id(rs, "application_id"),
                id(rs, "actor_id"), rs.getString("actor_role"), rs.getString("action"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), id(rs, "policy_version_id"),
                rs.getString("policy_hash"), id(rs, "assessment_id"), id(rs, "previous_snapshot_id"),
                id(rs, "new_snapshot_id"), rs.getString("ai_prompt_id"), rs.getObject("ai_prompt_version", Integer.class),
                rs.getString("ai_model"), rs.getString("previous_event_hash"), rs.getString("event_hash"));
    }

    private static UUID id(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }
}
