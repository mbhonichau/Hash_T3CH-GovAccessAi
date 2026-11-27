package za.gov.govaccess.policy.service;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.policy.entity.PolicyVersion;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.security.ActorIdentity;

@Service
public class PolicyVersionSelectionService {
    private final JdbcTemplate jdbc;
    private final PolicyVersionRepository versions;
    private final BusinessAuditService audit;

    public PolicyVersionSelectionService(JdbcTemplate jdbc, PolicyVersionRepository versions, BusinessAuditService audit) {
        this.jdbc = jdbc;
        this.versions = versions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Optional<PolicyVersion> select(String programmeCode, LocalDate applicationDate) {
        if (programmeCode == null || applicationDate == null) {
            throw new IllegalArgumentException("Programme and application date are required");
        }
        var override = jdbc.query("""
                SELECT v.id FROM govaccess.policy_transition_window t
                JOIN govaccess.programme p ON t.programme_id=p.id
                JOIN govaccess.policy_version v ON t.policy_version_id=v.id AND v.programme_id=p.id
                WHERE p.code=? AND ? BETWEEN t.starts_on AND t.ends_on
                AND v.status='PUBLISHED' AND v.effective_from<=?
                ORDER BY t.priority DESC,t.starts_on DESC,t.id LIMIT 1
                """, (rs, index) -> rs.getObject(1, UUID.class), programmeCode, applicationDate, applicationDate);
        if (!override.isEmpty()) {
            return versions.findById(override.getFirst());
        }
        return jdbc.query("""
                SELECT v.id FROM govaccess.policy_version v JOIN govaccess.programme p ON v.programme_id=p.id
                WHERE p.code=? AND v.status='PUBLISHED' AND v.effective_from<=?
                ORDER BY v.effective_from DESC,v.published_at DESC,v.id LIMIT 1
                """, (rs, index) -> rs.getObject(1, UUID.class), programmeCode, applicationDate)
                .stream().findFirst().flatMap(versions::findById);
    }

    @Transactional
    public UUID configureTransition(Authentication actor, UUID versionId, LocalDate start, LocalDate end, int priority) {
        ActorIdentity.require(actor, "POLICY_PUBLISH");
        var version = versions.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Policy version not found"));
        if (!"PUBLISHED".equals(version.getStatus()) || start == null || end == null
                || start.isBefore(version.getEffectiveFrom()) || end.isBefore(start)) {
            throw new IllegalArgumentException("Choose an effective, published policy and a valid date range");
        }
        var programmeId = version.getProgramme().getId();
        jdbc.queryForObject("SELECT id FROM govaccess.programme WHERE id=? FOR UPDATE", UUID.class, programmeId);
        var overlap = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM govaccess.policy_transition_window
                    WHERE programme_id=? AND priority=? AND starts_on<=? AND ends_on>=?)
                """, Boolean.class, programmeId, priority, end, start);
        if (Boolean.TRUE.equals(overlap)) {
            throw new IllegalArgumentException("Overlapping transition windows must have different priorities");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO govaccess.policy_transition_window
                (id,programme_id,policy_version_id,starts_on,ends_on,priority) VALUES (?,?,?,?,?,?)
                """, id, programmeId, versionId, start, end, priority);
        var identity = ActorIdentity.of(actor);
        audit.append(new Change(null, identity.id(), identity.role(), "POLICY_TRANSITION_CONFIGURED", versionId,
                version.getContentHash(), null, null,
                Map.of("windowId", id, "start", start.toString(), "end", end.toString(), "priority", priority),
                null, null, null));
        return id;
    }
}
