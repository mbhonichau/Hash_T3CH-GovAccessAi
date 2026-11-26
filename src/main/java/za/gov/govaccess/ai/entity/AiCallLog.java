package za.gov.govaccess.ai.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.enums.AiOutcome;

@Entity
@Table(name = "ai_call_log")
public class AiCallLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)
    private Instant createdAt;
    private UUID userId;
    @Column(nullable = false)
    private String promptId;
    private int promptVersion;
    private String provider;
    private String model;
    @Column(nullable = false, length = 64)
    private String inputSha256;
    @Column(length = 64)
    private String outputSha256;
    private long latencyMs;
    private Integer tokensIn;
    private Integer tokensOut;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private AiOutcome outcome;
    private String relatedEntityType;
    private UUID relatedEntityId;

    protected AiCallLog() {}

    public AiCallLog(AiRequest request, String provider, String model, String inputSha256,
                     String outputSha256, long latencyMs, Integer tokensIn, Integer tokensOut, AiOutcome outcome) {
        this.createdAt = Instant.now();
        this.userId = request.userId();
        this.promptId = request.promptId();
        this.promptVersion = request.promptVersion();
        this.provider = provider;
        this.model = model;
        this.inputSha256 = inputSha256;
        this.outputSha256 = outputSha256;
        this.latencyMs = latencyMs;
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
        this.outcome = outcome;
        this.relatedEntityType = request.relatedEntityType();
        this.relatedEntityId = request.relatedEntityId();
    }

    public AiOutcome getOutcome() { return outcome; }
    public String getInputSha256() { return inputSha256; }
    public String getOutputSha256() { return outputSha256; }
    public String getProvider() { return provider; }
}
