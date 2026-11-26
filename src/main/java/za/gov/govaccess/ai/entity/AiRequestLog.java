package za.gov.govaccess.ai.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.enums.AiOutcome;

@Entity
@Table(name = "ai_request_log")
public class AiRequestLog {
    @Id private UUID id;
    @Column(nullable = false) private Instant createdAt;
    private UUID userId;
    private String relatedEntityType;
    private UUID relatedEntityId;
    @Column(nullable = false) private String promptId;
    private int promptVersion;
    private String provider;
    private String model;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 32) private AiOutcome outcome;
    private int attemptCount;
    private long totalLatencyMs;
    @Column(nullable = false, length = 64) private String inputSha256;
    @Column(length = 64) private String outputSha256;

    protected AiRequestLog() {}

    public AiRequestLog(AiRequest request, String inputSha256) {
        id = UUID.randomUUID();
        createdAt = Instant.now();
        userId = request.context().userId();
        relatedEntityType = request.context().relatedEntityType();
        relatedEntityId = request.context().relatedEntityId();
        promptId = request.promptId();
        promptVersion = request.promptVersion();
        this.inputSha256 = inputSha256;
    }

    public void finish(String provider, String model, AiOutcome outcome, int attemptCount,
                       long totalLatencyMs, String outputSha256) {
        this.provider = provider;
        this.model = model;
        this.outcome = outcome;
        this.attemptCount = attemptCount;
        this.totalLatencyMs = totalLatencyMs;
        this.outputSha256 = outputSha256;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getRelatedEntityId() { return relatedEntityId; }
    public AiOutcome getOutcome() { return outcome; }
    public int getAttemptCount() { return attemptCount; }
}
