package za.gov.govaccess.rules.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record RuleEvaluation(String ruleCode, Status status, boolean mandatory,
                             JsonNode expectedValue, JsonNode actualValue, List<String> evidenceRefs) {
    public RuleEvaluation {
        evidenceRefs = List.copyOf(evidenceRefs);
    }

    public enum Status { PASS, FAIL, UNKNOWN, CONFLICT, NOT_APPLICABLE }
}
