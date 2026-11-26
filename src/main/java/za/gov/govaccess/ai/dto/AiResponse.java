package za.gov.govaccess.ai.dto;

public record AiResponse(String rawText, String model, String provider, long latencyMs,
                         Integer tokensIn, Integer tokensOut) {}
