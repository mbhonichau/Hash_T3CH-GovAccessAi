package za.gov.govaccess.policy.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import za.gov.govaccess.policy.dto.PolicyImportJob;
import za.gov.govaccess.policy.dto.PolicyImportJob.Status;

@Repository
public class PolicyImportRepository {
    private static final String SUMMARY = """
            SELECT id,user_id,draft_id,programme_code,version_label,source_label,status,
                   completed_chunks,total_chunks,failure,updated_at
            FROM govaccess.policy_import_job
            """;

    private final JdbcTemplate jdbc;

    public PolicyImportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Source(String text, byte[] pdf) {}
    public record Edit(String ruleCode, String editor, String originalValue, String previousValue, String newValue) {}

    public void enqueue(PolicyImportJob job, Source source) {
        jdbc.update("""
                INSERT INTO govaccess.policy_import_job
                (id,user_id,draft_id,programme_code,version_label,source_label,status,source_text,source_pdf)
                VALUES (?,?,?,?,?,?,'QUEUED',?,?)
                """, job.id(), job.userId(), job.draftId(), job.programmeCode(), job.versionLabel(),
                job.sourceLabel(), source.text(), source.pdf());
    }

    public Optional<PolicyImportJob> owned(UUID id, UUID userId, boolean lock) {
        return jdbc.query(SUMMARY + " WHERE id=? AND user_id=?" + (lock ? " FOR UPDATE" : ""),
                PolicyImportRepository::map, id, userId).stream().findFirst();
    }

    public List<PolicyImportJob> recent(UUID userId) {
        return jdbc.query(SUMMARY + " WHERE user_id=? ORDER BY updated_at DESC,id LIMIT 20",
                PolicyImportRepository::map, userId);
    }

    public Optional<PolicyImportJob> queued() {
        return jdbc.query(SUMMARY + " WHERE status='QUEUED' ORDER BY updated_at,id LIMIT 1",
                PolicyImportRepository::map).stream().findFirst();
    }

    public void expireRunningBefore(Instant cutoff) {
        jdbc.update("""
                UPDATE govaccess.policy_import_job SET status='FAILED',
                failure='Import processing was interrupted. Submit the source again.',updated_at=CURRENT_TIMESTAMP
                WHERE status='RUNNING' AND updated_at<?
                """, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
    }

    public boolean claim(UUID id) {
        return jdbc.update("""
                UPDATE govaccess.policy_import_job SET status='RUNNING',updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='QUEUED'
                """, id) == 1;
    }

    public Source source(UUID id) {
        return jdbc.queryForObject("""
                SELECT source_text,source_pdf FROM govaccess.policy_import_job WHERE id=?
                """, (rs, index) -> new Source(rs.getString(1), rs.getBytes(2)), id);
    }

    public void progress(UUID id, int complete, int total) {
        jdbc.update("""
                UPDATE govaccess.policy_import_job SET completed_chunks=?,total_chunks=?,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='RUNNING'
                """, complete, total, id);
    }

    public void succeed(UUID id, String review) {
        if (jdbc.update("""
                UPDATE govaccess.policy_import_job SET status='SUCCEEDED',review_json=?,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='RUNNING'
                """, review, id) != 1) {
            throw new IllegalStateException("Import job is no longer running");
        }
    }

    public void fail(UUID id, String message) {
        jdbc.update("""
                UPDATE govaccess.policy_import_job SET status='FAILED',failure=?,updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='RUNNING'
                """, message, id);
    }

    public String review(UUID id) {
        return jdbc.queryForObject("SELECT review_json FROM govaccess.policy_import_job WHERE id=?", String.class, id);
    }

    public boolean instructionLike(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT instruction_like_content FROM govaccess.policy_import_job WHERE id=?", Boolean.class, id));
    }

    public void addEdit(UUID jobId, UUID editorId, Edit edit) {
        jdbc.update("""
                INSERT INTO govaccess.policy_import_rule_edit
                (id,job_id,rule_code,editor_id,editor_label,original_value,previous_value,new_value)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), jobId, edit.ruleCode(), editorId, edit.editor(), edit.originalValue(),
                edit.previousValue(), edit.newValue());
    }

    public List<Edit> edits(UUID jobId) {
        return jdbc.query("""
                SELECT rule_code,editor_label,original_value,previous_value,new_value
                FROM govaccess.policy_import_rule_edit WHERE job_id=? ORDER BY edited_at DESC,id DESC
                """, (rs, index) -> new Edit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5)), jobId);
    }

    private static PolicyImportJob map(ResultSet rs, int index) throws SQLException {
        return new PolicyImportJob(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("draft_id", UUID.class), rs.getString("programme_code"), rs.getString("version_label"),
                rs.getString("source_label"), Status.valueOf(rs.getString("status")), rs.getInt("completed_chunks"),
                rs.getInt("total_chunks"), rs.getString("failure"), rs.getTimestamp("updated_at").toInstant());
    }
}
