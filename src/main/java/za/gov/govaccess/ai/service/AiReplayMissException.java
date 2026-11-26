package za.gov.govaccess.ai.service;

public class AiReplayMissException extends RuntimeException {
    public AiReplayMissException() {
        super("No recorded AI response matched the request");
    }
}
