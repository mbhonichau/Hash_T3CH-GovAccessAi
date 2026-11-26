package za.gov.govaccess.rules.service;

import za.gov.govaccess.rules.dto.FinancialScreenFacts;
import za.gov.govaccess.rules.dto.FinancialScreenResult;
import za.gov.govaccess.rules.enums.IncomePathway;
import za.gov.govaccess.rules.model.FinancialScreenPolicy;

import static za.gov.govaccess.rules.dto.FinancialScreenResult.Outcome.FAILS_FINANCIAL_SCREEN;
import static za.gov.govaccess.rules.dto.FinancialScreenResult.Outcome.NEEDS_REVIEW;
import static za.gov.govaccess.rules.dto.FinancialScreenResult.Outcome.PASSES_FINANCIAL_SCREEN;

public class LegacyFinancialScreenReference {
    public FinancialScreenResult evaluate(FinancialScreenPolicy policy, FinancialScreenFacts facts) {
        var grantRule = policy.qualifyingSassaGrant();
        var grant = facts.verifiedSassaGrant();
        if (grant != null && grantRule.value().contains(grant)) {
            return new FinancialScreenResult(PASSES_FINANCIAL_SCREEN, grantRule.ruleKey(), grantRule.sourceSection(),
                    "A verified qualifying SASSA grant satisfies the financial criterion.");
        }

        if (facts.annualGrossHouseholdIncome() == null || facts.verifiedIncomePathway() == null) {
            return new FinancialScreenResult(NEEDS_REVIEW, null, null,
                    "Verified annual gross household income and an applicable income pathway are required.");
        }

        var incomeRule = facts.verifiedIncomePathway() == IncomePathway.STANDARD
                ? policy.standardIncome() : policy.disabilityIncome();
        if (facts.annualGrossHouseholdIncome().compareTo(incomeRule.value()) <= 0) {
            return new FinancialScreenResult(PASSES_FINANCIAL_SCREEN, incomeRule.ruleKey(), incomeRule.sourceSection(),
                    "Verified annual gross household income is within the applicable cap.");
        }
        if (grant == null) {
            return new FinancialScreenResult(NEEDS_REVIEW, grantRule.ruleKey(), grantRule.sourceSection(),
                    "Income exceeds the applicable cap and SASSA grant status is unverified.");
        }
        return new FinancialScreenResult(FAILS_FINANCIAL_SCREEN, incomeRule.ruleKey(), incomeRule.sourceSection(),
                "Verified annual gross household income exceeds the applicable cap.");
    }
}
