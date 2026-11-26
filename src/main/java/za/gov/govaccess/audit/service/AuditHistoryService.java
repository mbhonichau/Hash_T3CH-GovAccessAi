package za.gov.govaccess.audit.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.security.ActorIdentity;
import za.gov.govaccess.security.ManagedRole;

@Service
public class AuditHistoryService {
    private final JdbcTemplate jdbc;
    private final BusinessAuditService audit;

    public record Summary(UUID id, String action, String actorRole, OffsetDateTime occurredAt,
                          UUID policyVersionId, String eventHash) {}

    public AuditHistoryService(JdbcTemplate jdbc, BusinessAuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public boolean mayView(Authentication actor) {
        return ActorIdentity.has(actor, "AUDIT_VIEW_ALL") || ActorIdentity.has(actor, "AUDIT_VIEW_LIMITED");
    }

    public List<Summary> visible(Authentication actor) {
        if (!mayView(actor)) {
            throw new AccessDeniedException("Audit history permission is required");
        }
        var all = ActorIdentity.has(actor, "AUDIT_VIEW_ALL");
        return jdbc.query("""
                SELECT e.id,e.action,e.actor_role,e.occurred_at,e.policy_version_id,e.event_hash
                FROM govaccess.audit_event e LEFT JOIN govaccess.policy_version p ON p.id=e.policy_version_id
                WHERE ? OR EXISTS (
                    SELECT 1 FROM govaccess.audit_scope_assignment s WHERE s.actor_id=?
                    AND ((s.application_id IS NOT NULL AND s.application_id=e.application_id)
                      OR (s.programme_id IS NOT NULL AND s.programme_id=p.programme_id)))
                ORDER BY e.event_sequence DESC LIMIT 100
                """, (rs, index) -> new Summary(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class), rs.getObject(5, UUID.class), rs.getString(6)),
                all, ActorIdentity.of(actor).id());
    }

    @Transactional
    public void grantScope(Authentication actor, UUID userId, UUID applicationId, UUID programmeId) {
        ActorIdentity.require(actor, "USER_MANAGE");
        if ((applicationId == null) == (programmeId == null)) {
            throw new IllegalArgumentException("Choose exactly one scope");
        }
        var role = jdbc.query("SELECT role FROM govaccess.managed_user WHERE id=? FOR UPDATE",
                (rs, index) -> ManagedRole.valueOf(rs.getString(1)), userId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account not found"));
        if (applicationId != null && role != ManagedRole.ASSESSMENT_OFFICER
                || programmeId != null && role != ManagedRole.POLICY_ADMINISTRATOR) {
            throw new IllegalArgumentException("Case scopes are for officers and programme scopes are for policy administrators");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO govaccess.audit_scope_assignment (id,actor_id,application_id,programme_id) VALUES (?,?,?,?)
                """, id, userId, applicationId, programmeId);
        var identity = ActorIdentity.of(actor);
        audit.append(new Change(applicationId, identity.id(), identity.role(), "AUDIT_SCOPE_GRANTED",
                null, null, null, null,
                Map.of("scopeId", id, "targetActorId", userId, "scopeTargetId", applicationId == null ? programmeId : applicationId),
                null, null, null));
    }
}
