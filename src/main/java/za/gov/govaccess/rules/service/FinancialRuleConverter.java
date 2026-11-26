package za.gov.govaccess.rules.service;

import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import za.gov.govaccess.rules.enums.RuleOperator;
import za.gov.govaccess.rules.model.FinancialScreenPolicy;
import za.gov.govaccess.rules.model.RuleDocument;
import za.gov.govaccess.rules.model.RuleDocument.Condition;
import za.gov.govaccess.rules.model.RuleDocument.Group;
import za.gov.govaccess.rules.model.RuleDocument.OnMissingEvidence;
import za.gov.govaccess.rules.model.RuleDocument.Rule;
import za.gov.govaccess.rules.model.RuleDocument.Source;

public final class FinancialRuleConverter {
    public static final String SOURCE_DOCUMENT = "NSFAS Eligibility Criteria and Conditions for Financial Aid, 2025 Academic Year";
    public static final String NOTICE = "DEMO DATA ONLY. Verify the figures against the official policy before use.";
    public static final String DEMO_DOCUMENT = "NSFAS_DEMO_FACT";
    public static final String GRANT_FIELD = "nsfas.verifiedSassaGrant";
    public static final String INCOME_FIELD = "nsfas.annualGrossHouseholdIncome";
    public static final String PATHWAY_FIELD = "nsfas.verifiedIncomePathway";
    public static final String INCOME_UNIT = "ZAR/year";

    private FinancialRuleConverter() {}

    public static RuleDocument convert(FinancialScreenPolicy legacy) {
        var grant = legacy.qualifyingSassaGrant();
        var grants = JsonNodeFactory.instance.arrayNode();
        grant.value().stream().sorted().forEach(value -> grants.add(value.name()));
        var grantRule = new Rule(grant.ruleKey(), "Qualifying SASSA grant", GRANT_FIELD, RuleOperator.IN_SET, grants,
                null, true, List.of(), OnMissingEvidence.UNKNOWN, source(grant.sourceSection(), grant.sourceQuote()),
                null, null);
        var income = incomeRule(legacy.standardIncome(), "Standard income cap", "STANDARD");
        var restricted = incomeRule(legacy.disabilityIncome(), "Disability-pathway income cap", "DISABILITY");
        String groupCode = "nsfas-financial-screen";
        var codes = List.of(grantRule.ruleCode(), income.ruleCode(), restricted.ruleCode());
        while (codes.contains(groupCode)) {
            groupCode += "-group";
        }
        var group = new Rule(groupCode, "Financial screening pathways", null, RuleOperator.OR, null, null, true,
                List.of(), OnMissingEvidence.UNKNOWN, grantRule.source(),
                new Group(RuleOperator.OR, List.of(grantRule, income, restricted)), null);
        return new RuleDocument(RuleDocument.CURRENT_VERSION, List.of(group), NOTICE);
    }

    private static Rule incomeRule(FinancialScreenPolicy.IncomeRule rule, String name, String pathway) {
        return new Rule(rule.ruleKey(), name, INCOME_FIELD, RuleOperator.LESS_THAN_OR_EQUAL,
                DecimalNode.valueOf(rule.value()), INCOME_UNIT, true, List.of(), OnMissingEvidence.UNKNOWN,
                source(rule.sourceSection(), rule.sourceQuote()), null,
                new Condition(PATHWAY_FIELD, TextNode.valueOf(pathway)));
    }

    private static Source source(String section, String quote) {
        return new Source(SOURCE_DOCUMENT, section, 19, quote);
    }
}
