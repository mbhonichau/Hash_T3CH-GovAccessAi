package za.gov.govaccess.rules.service;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.gov.govaccess.rules.entity.FieldDefinition;
import za.gov.govaccess.rules.enums.FieldValueType;
import za.gov.govaccess.rules.enums.RuleOperator;
import za.gov.govaccess.rules.model.RuleDocument;
import za.gov.govaccess.rules.model.RuleDocument.Rule;

@Component
public class RuleDocumentValidator {
    private final int maxDepth;

    public RuleDocumentValidator(@Value("${govaccess.rules.max-group-depth:5}") int maxDepth) {
        if (maxDepth < 0 || maxDepth > 50) {
            throw new IllegalArgumentException("Maximum group depth must be between zero and 50");
        }
        this.maxDepth = maxDepth;
    }

    public void validate(RuleDocument document, Map<String, FieldDefinition> fields, Set<String> documentTypes) {
        var codes = new HashSet<String>();
        document.rules().forEach(rule -> validate(rule, fields, documentTypes, codes, 0));
    }

    private void validate(Rule rule, Map<String, FieldDefinition> fields, Set<String> types,
                          Set<String> codes, int depth) {
        if (blank(rule.ruleCode()) || blank(rule.name()) || !codes.add(rule.ruleCode()) || rule.operator() == null
                || blank(rule.source().document()) || blank(rule.source().section()) || blank(rule.source().quote())
                || rule.source().page() != null && rule.source().page() < 1) {
            throw new IllegalArgumentException("Rules require unique codes, names, operators and valid source citations");
        }
        if (!types.containsAll(rule.requiredEvidence())) {
            throw new IllegalArgumentException("Unknown required document type in " + rule.ruleCode());
        }
        if (rule.condition() != null) {
            var conditionField = field(fields, rule.condition().fieldKey());
            RuleValues.normalize(rule.condition().expectedValue(), conditionField.getValueType());
        }
        if (rule.operator().isGroup()) {
            if (rule.group() == null || rule.group().type() != rule.operator() || depth >= maxDepth
                    || rule.fieldKey() != null || rule.unit() != null
                    || rule.expectedValue() != null && !rule.expectedValue().isNull()) {
                throw new IllegalArgumentException("Invalid rule group or maximum group depth exceeded");
            }
            rule.children().forEach(child -> validate(child, fields, types, codes, depth + 1));
            return;
        }
        if (rule.group() != null) {
            throw new IllegalArgumentException("Only AND or OR can have child rules");
        }
        if (rule.operator() == RuleOperator.DOCUMENT_REQUIRED) {
            var expected = rule.expectedValue();
            if (rule.fieldKey() != null || rule.unit() != null
                    || expected == null || !expected.isTextual() || !types.contains(expected.textValue())) {
                throw new IllegalArgumentException("DOCUMENT_REQUIRED needs a known document type and no field or unit");
            }
            return;
        }
        var field = field(fields, rule.fieldKey());
        if (!Objects.equals(rule.unit(), field.getUnit())) {
            throw new IllegalArgumentException("Rule unit must match the field catalogue");
        }
        validateExpectation(rule, field.getValueType());
    }

    private static void validateExpectation(Rule rule, FieldValueType type) {
        var value = rule.expectedValue();
        boolean numeric = type == FieldValueType.INTEGER || type == FieldValueType.DECIMAL || type == FieldValueType.MONEY;
        switch (rule.operator()) {
            case BETWEEN, DATE_WITHIN -> {
                if (rule.operator() == RuleOperator.BETWEEN ? !numeric : type != FieldValueType.DATE) {
                    throw new IllegalArgumentException("Range operator does not match the field type");
                }
                if (value == null || !value.isArray() || value.size() != 2
                        || RuleValues.compare(RuleValues.normalize(value.get(0), type),
                                RuleValues.normalize(value.get(1), type)) > 0) {
                    throw new IllegalArgumentException("A range needs two ordered bounds");
                }
            }
            case IN_SET -> {
                if (value == null || !value.isArray() || value.isEmpty()) {
                    throw new IllegalArgumentException("IN_SET requires a nonempty list");
                }
                value.forEach(item -> RuleValues.normalize(item, type));
            }
            case IS_TRUE -> {
                if (type != FieldValueType.BOOLEAN || value == null || !value.isBoolean() || !value.booleanValue()) {
                    throw new IllegalArgumentException("IS_TRUE requires a Boolean field and an expected value of true");
                }
            }
            case GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL -> {
                if (!numeric) {
                    throw new IllegalArgumentException("Numeric comparison requires a numeric field");
                }
                RuleValues.normalize(value, type);
            }
            default -> RuleValues.normalize(value, type);
        }
    }

    private static FieldDefinition field(Map<String, FieldDefinition> fields, String key) {
        if (key == null || !fields.containsKey(key)) {
            throw new IllegalArgumentException("Unknown rule field: " + key);
        }
        return fields.get(key);
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
