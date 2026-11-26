package za.gov.govaccess.rules.enums;

public enum RuleOperator {
    GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL, EQUALS, NOT_EQUALS, BETWEEN, IN_SET,
    DOCUMENT_REQUIRED, IS_TRUE, DATE_WITHIN, AND, OR;

    public boolean isGroup() {
        return this == AND || this == OR;
    }
}
