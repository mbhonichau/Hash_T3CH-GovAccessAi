package za.gov.govaccess.rules.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import za.gov.govaccess.rules.enums.FieldValueType;

final class RuleValues {
    private RuleValues() {}

    static JsonNode normalize(JsonNode value, FieldValueType type) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("A rule value is required");
        }
        return switch (type) {
            case TEXT -> {
                require(value.isTextual(), type);
                yield value;
            }
            case BOOLEAN -> {
                require(value.isBoolean(), type);
                yield value;
            }
            case INTEGER, DECIMAL, MONEY -> {
                require(value.isNumber() && (type != FieldValueType.INTEGER || value.isIntegralNumber()), type);
                yield DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
            }
            case DATE -> {
                require(value.isTextual(), type);
                try {
                    yield TextNode.valueOf(LocalDate.parse(value.textValue()).toString());
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Rule dates must be ISO local dates", e);
                }
            }
        };
    }

    static int compare(JsonNode left, JsonNode right) {
        return left.isNumber() ? left.decimalValue().compareTo(right.decimalValue())
                : LocalDate.parse(left.textValue()).compareTo(LocalDate.parse(right.textValue()));
    }

    private static void require(boolean valid, FieldValueType type) {
        if (!valid) {
            throw new IllegalArgumentException("Rule value must match field type " + type);
        }
    }
}
