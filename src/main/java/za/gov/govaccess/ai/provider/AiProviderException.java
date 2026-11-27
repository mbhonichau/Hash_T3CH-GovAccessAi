package za.gov.govaccess.ai.provider;

public class AiProviderException extends RuntimeException {
    public enum Reason { NOT_CONFIGURED, HTTP_ERROR, UNAVAILABLE, BLOCKED, DISABLED, RATE_LIMITED }

    private final Reason reason;
    private final int status;

    public AiProviderException(String message) {
        this(Reason.UNAVAILABLE, 0, message);
    }

    public AiProviderException(Reason reason, String message) {
        this(reason, 0, message);
    }

    public AiProviderException(int status) {
        this(Reason.HTTP_ERROR, status, "AI provider returned HTTP " + status);
    }

    private AiProviderException(Reason reason, int status, String message) {
        super(message);
        this.reason = reason;
        this.status = status;
    }

    public Reason reason() { return reason; }
    public int status() { return status; }
}
