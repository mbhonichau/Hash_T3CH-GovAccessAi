package za.gov.govaccess.rules.dto;

import java.math.BigDecimal;
import za.gov.govaccess.rules.enums.IncomePathway;
import za.gov.govaccess.rules.enums.VerifiedSassaGrant;

/** Verified inputs only. A null component means its evidence has not been verified. */
public record FinancialScreenFacts(BigDecimal annualGrossHouseholdIncome,
                                   VerifiedSassaGrant verifiedSassaGrant,
                                   IncomePathway verifiedIncomePathway) {
    public FinancialScreenFacts {
        if (annualGrossHouseholdIncome != null && annualGrossHouseholdIncome.signum() < 0) {
            throw new IllegalArgumentException("Annual gross household income cannot be negative");
        }
    }
}
