package za.gov.govaccess.ai.service;

import java.util.List;

public class AiOutputInvalidException extends RuntimeException {
    private final List<String> errors;

    public AiOutputInvalidException(List<String> errors) {
        super("AI output failed validation");
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
