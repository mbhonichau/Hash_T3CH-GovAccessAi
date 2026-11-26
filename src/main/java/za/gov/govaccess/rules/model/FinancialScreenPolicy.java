package za.gov.govaccess.rules.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import za.gov.govaccess.rules.enums.VerifiedSassaGrant;

public record FinancialScreenPolicy(GrantRule qualifyingSassaGrant, IncomeRule standardIncome,
                                    IncomeRule disabilityIncome) {
    public FinancialScreenPolicy {
        Objects.requireNonNull(qualifyingSassaGrant);
        Objects.requireNonNull(standardIncome);
        Objects.requireNonNull(disabilityIncome);
        if (Set.copyOf(List.of(qualifyingSassaGrant.ruleKey(), standardIncome.ruleKey(), disabilityIncome.ruleKey()))
                .size() != 3) {
            throw new IllegalArgumentException("Financial screening rule keys must be unique");
        }
    }

    public record GrantRule(String ruleKey, Set<VerifiedSassaGrant> value, String sourceSection, String sourceQuote) {
        public GrantRule {
            value = Set.copyOf(value);
            if (value.isEmpty() || value.contains(VerifiedSassaGrant.NONE_OR_NON_QUALIFYING)) {
                throw new IllegalArgumentException("A grant rule must list qualifying grants");
            }
            requireCitation(ruleKey, sourceSection, sourceQuote);
        }
    }

    public record IncomeRule(String ruleKey, BigDecimal value, String sourceSection, String sourceQuote) {
        public IncomeRule {
            if (value == null || value.signum() < 0) {
                throw new IllegalArgumentException("An income rule requires a nonnegative annual ZAR cap");
            }
            requireCitation(ruleKey, sourceSection, sourceQuote);
        }
    }

    private static void requireCitation(String ruleKey, String section, String quote) {
        if (ruleKey == null || ruleKey.isBlank() || section == null || section.isBlank()
                || quote == null || quote.isBlank()) {
            throw new IllegalArgumentException("Each rule requires a key, source section and quote");
        }
    }
}
