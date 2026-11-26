package za.gov.govaccess.rules.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Verified evidence only. A null field key and value designate a verified document. */
public record VerifiedRuleEvidence(String reference, String documentType, String fieldKey, JsonNode value) {
    public VerifiedRuleEvidence {
        if (Objects.requireNonNull(reference).isBlank() || Objects.requireNonNull(documentType).isBlank()) {
            throw new IllegalArgumentException("A verified evidence reference and document type are required");
        }
    }
}
