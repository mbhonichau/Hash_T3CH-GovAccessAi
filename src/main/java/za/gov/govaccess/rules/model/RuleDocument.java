package za.gov.govaccess.rules.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import za.gov.govaccess.rules.enums.RuleOperator;

public record RuleDocument(int schemaVersion, List<Rule> rules, String notice) {
    public static final int CURRENT_VERSION = 1;
    public RuleDocument {
        if (schemaVersion != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported rule document schema version");
        }
        rules = List.copyOf(rules);
    }

    public record Rule(String ruleCode, String name, String fieldKey, RuleOperator operator,
                       JsonNode expectedValue, String unit, boolean mandatory, List<String> requiredEvidence,
                       OnMissingEvidence onMissingEvidence, Source source, Group group, Condition condition) {
        public Rule {
            requiredEvidence = List.copyOf(Objects.requireNonNull(requiredEvidence));
            Objects.requireNonNull(onMissingEvidence);
            Objects.requireNonNull(source);
        }

        public List<Rule> children() { return group == null ? List.of() : group.rules(); }
    }

    public enum OnMissingEvidence { UNKNOWN, NEEDS_REVIEW }

    public record Source(String document, String section, Integer page, String quote) {}

    public record Group(RuleOperator type, List<Rule> rules) {
        public Group {
            rules = List.copyOf(rules);
            if (type == null || !type.isGroup() || rules.isEmpty()) {
                throw new IllegalArgumentException("A rule group requires AND or OR and at least one child");
            }
        }
    }

    public record Condition(String fieldKey, JsonNode expectedValue) {}
}
