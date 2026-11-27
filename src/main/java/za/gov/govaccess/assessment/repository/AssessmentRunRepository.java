package za.gov.govaccess.assessment.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import za.gov.govaccess.assessment.dto.AssessmentRun;
import za.gov.govaccess.rules.service.RecommendationService.Recommendation;

@Repository
public class AssessmentRunRepository {
    private final JdbcTemplate jdbc;

    public AssessmentRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AssessmentRun run, UUID assessorId) {
        jdbc.update("""
                INSERT INTO govaccess.assessment_policy_run
                    (id, assessor_id, application_date, assessed_at, policy_version_id, policy_hash, recommendation)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, run.id(), assessorId, run.applicationDate(), run.assessedAt(),
                run.policyVersionId(), run.policyHash(), run.recommendation().name());
    }

    public Optional<AssessmentRun> findOwned(UUID id, UUID assessorId) {
        return jdbc.query("""
                SELECT r.id, r.application_date, r.assessed_at, r.policy_version_id, p.version_label,
                    r.policy_hash, r.recommendation
                FROM govaccess.assessment_policy_run r JOIN govaccess.policy_version p ON p.id=r.policy_version_id
                WHERE r.id=? AND r.assessor_id=?
                """, (row, number) -> new AssessmentRun(row.getObject("id", UUID.class),
                        row.getObject("application_date", LocalDate.class), row.getObject("assessed_at", OffsetDateTime.class),
                        row.getObject("policy_version_id", UUID.class), row.getString("version_label"),
                        row.getString("policy_hash"), Recommendation.valueOf(row.getString("recommendation"))),
                id, assessorId).stream().findFirst();
    }
}
