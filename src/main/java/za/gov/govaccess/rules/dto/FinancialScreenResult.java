package za.gov.govaccess.rules.dto;

public record FinancialScreenResult(Outcome outcome, String ruleKey, String sourceSection, String reason) {
    public enum Outcome { PASSES_FINANCIAL_SCREEN, FAILS_FINANCIAL_SCREEN, NEEDS_REVIEW }
}
