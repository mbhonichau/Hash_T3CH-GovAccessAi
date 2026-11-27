package za.gov.govaccess.ai.provider;

public final class AiRateLimitException extends AiProviderException {
    public AiRateLimitException() {
        super(Reason.RATE_LIMITED, "AI request limit reached. Try again when your request budget resets.");
    }
}
