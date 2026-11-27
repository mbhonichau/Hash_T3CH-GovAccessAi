package za.gov.govaccess.document.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public record ExtractedField(JsonNode value, double confidence, String sourceSnippet, Integer pageNumber,
                             UUID documentId, int documentVersion, List<String> affectsRuleCodes,
                             Validation validation, boolean humanCorrected, boolean verified) {
    public ExtractedField {
        if (value != null && value.isNull()) value = null;
        affectsRuleCodes = List.copyOf(affectsRuleCodes);
    }

    public enum Validation { PASSED, WARNING, FAILED }
}
