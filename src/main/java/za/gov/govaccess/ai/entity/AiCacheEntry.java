package za.gov.govaccess.ai.entity;

import jakarta.persistence.*;
import za.gov.govaccess.ai.dto.AiResponse;

@Entity
@Table(name = "ai_cache")
public class AiCacheEntry {
    @Id
    @Column(name = "cache_key", length = 64)
    private String key;
    @Column(nullable = false, columnDefinition = "text")
    private String rawText;
    @Column(nullable = false)
    private String model;
    @Column(nullable = false)
    private String provider;
    private Integer tokensIn;
    private Integer tokensOut;

    protected AiCacheEntry() {}

    public AiCacheEntry(String key, AiResponse response) {
        this.key = key;
        this.rawText = response.rawText();
        this.model = response.model();
        this.provider = response.provider();
        this.tokensIn = response.tokensIn();
        this.tokensOut = response.tokensOut();
    }

    public AiResponse response() {
        return new AiResponse(rawText, model, provider, 0, tokensIn, tokensOut);
    }
}
