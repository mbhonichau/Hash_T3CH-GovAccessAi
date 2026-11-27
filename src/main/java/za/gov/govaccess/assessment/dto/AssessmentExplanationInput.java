package za.gov.govaccess.assessment.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import za.gov.govaccess.rules.dto.RuleEvaluation;

/** Minimal, persisted projection for generating an explanation. */
public record AssessmentExplanationInput(String policyVersion, String recommendation, List<EvaluatedRule> rules) {
    public record Source(String documentId, Integer pageNumber, String reference, JsonNode value) {
        public Source { value = EvaluatedRule.structured(value); }
        public String citation() { return "Document " + documentId + ", " + (pageNumber == null ? "page unavailable" : "page " + pageNumber); }
    }

    public record EvaluatedRule(String ruleCode, RuleEvaluation.Status status, JsonNode expectedValue, JsonNode actualValue,
                                List<String> evidenceUsed, String requirementOperator, List<Source> sources) {
        public EvaluatedRule {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }

        public EvaluatedRule(String ruleCode, RuleEvaluation.Status status, JsonNode expectedValue, JsonNode actualValue,
                             List<String> evidenceUsed) {
            this(ruleCode, status, expectedValue, actualValue, evidenceUsed, null, List.of());
        }

        public static EvaluatedRule from(RuleEvaluation evaluation, String operator, List<Source> sources) {
            return new EvaluatedRule(evaluation.ruleCode(), evaluation.status(), structured(evaluation.expectedValue()),
                    structured(evaluation.actualValue()), evaluation.evidenceRefs(), operator, sources);
        }

        private static JsonNode structured(JsonNode value) {
            if (value == null || value.isNull()) return null;
            if (value.isNumber() || value.isBoolean()) return value;
            if (value.isTextual() && value.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return value;
            if (value.isArray() && java.util.stream.StreamSupport.stream(value.spliterator(), false)
                    .allMatch(item -> item.isNumber() || item.isBoolean() || item.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) {
                return value;
            }
            return null;
        }
    }
}
