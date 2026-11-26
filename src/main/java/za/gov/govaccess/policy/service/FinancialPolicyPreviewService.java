package za.gov.govaccess.policy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import za.gov.govaccess.policy.entity.PolicyVersion;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.rules.dto.FinancialScreenFacts;
import za.gov.govaccess.rules.dto.FinancialScreenResult;
import za.gov.govaccess.rules.dto.RuleEvaluation;
import za.gov.govaccess.rules.dto.RuleEvaluation.Status;
import za.gov.govaccess.rules.dto.VerifiedRuleEvidence;
import za.gov.govaccess.rules.enums.IncomePathway;
import za.gov.govaccess.rules.model.FinancialScreenPolicy;
import za.gov.govaccess.rules.service.FinancialRuleConverter;

import static za.gov.govaccess.rules.dto.FinancialScreenResult.Outcome.*;

@Service
public class FinancialPolicyPreviewService {
    private final PolicyVersionRepository policies;
    private final ObjectReader reader;
    private final RulePolicyService rulePolicies;

    public FinancialPolicyPreviewService(PolicyVersionRepository policies, ObjectMapper mapper,
                                         RulePolicyService rulePolicies) {
        this.policies = policies;
        this.reader = mapper.readerFor(FinancialScreenPolicy.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.rulePolicies = rulePolicies;
    }

    public FinancialScreenResult preview(String programmeCode, String versionLabel, FinancialScreenFacts facts) {
        var version = policies.findByProgrammeCodeAndVersionLabel(programmeCode, versionLabel)
                .orElseThrow(() -> new IllegalArgumentException("Policy version not found"));
        return evaluateCanonical(version, facts);
    }

    private FinancialScreenResult evaluateCanonical(PolicyVersion version, FinancialScreenFacts facts) {
        var document = rulePolicies.read(version.getContent());
        var root = document.rules().getFirst();
        var evidence = new ArrayList<VerifiedRuleEvidence>();
        String type = FinancialRuleConverter.DEMO_DOCUMENT;
        if (facts.verifiedSassaGrant() != null) {
            evidence.add(new VerifiedRuleEvidence("preview-grant", type, FinancialRuleConverter.GRANT_FIELD,
                    TextNode.valueOf(facts.verifiedSassaGrant().name())));
        }
        if (facts.annualGrossHouseholdIncome() != null) {
            evidence.add(new VerifiedRuleEvidence("preview-income", type, FinancialRuleConverter.INCOME_FIELD,
                    DecimalNode.valueOf(facts.annualGrossHouseholdIncome())));
        }
        if (facts.verifiedIncomePathway() != null) {
            evidence.add(new VerifiedRuleEvidence("preview-pathway", type, FinancialRuleConverter.PATHWAY_FIELD,
                    TextNode.valueOf(facts.verifiedIncomePathway().name())));
        }
        var results = rulePolicies.evaluate(document, evidence).evaluations().stream()
                .collect(Collectors.toMap(RuleEvaluation::ruleCode, Function.identity()));
        var grant = root.children().getFirst();
        if (results.get(grant.ruleCode()).status() == Status.PASS) {
            return new FinancialScreenResult(PASSES_FINANCIAL_SCREEN, grant.ruleCode(), grant.source().section(),
                    "A verified qualifying SASSA grant satisfies the financial criterion.");
        }
        if (facts.annualGrossHouseholdIncome() == null || facts.verifiedIncomePathway() == null) {
            return new FinancialScreenResult(NEEDS_REVIEW, null, null,
                    "Verified annual gross household income and an applicable income pathway are required.");
        }
        var income = root.children().get(facts.verifiedIncomePathway() == IncomePathway.STANDARD ? 1 : 2);
        return switch (results.get(root.ruleCode()).status()) {
            case PASS -> new FinancialScreenResult(PASSES_FINANCIAL_SCREEN, income.ruleCode(), income.source().section(),
                    "Verified annual gross household income is within the applicable cap.");
            case FAIL -> new FinancialScreenResult(FAILS_FINANCIAL_SCREEN, income.ruleCode(), income.source().section(),
                    "Verified annual gross household income exceeds the applicable cap.");
            default -> new FinancialScreenResult(NEEDS_REVIEW, grant.ruleCode(), grant.source().section(),
                    "Income exceeds the applicable cap and SASSA grant status is unverified.");
        };
    }

    public FinancialScreenPolicy readRules(PolicyVersion version) {
        return readRules(version.getRuleJson());
    }

    public FinancialScreenPolicy readRules(String json) {
        try {
            FinancialScreenPolicy policy = reader.readValue(json);
            if (policy == null) {
                throw new IllegalStateException("Stored financial screening policy is empty");
            }
            return policy;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored financial screening policy is invalid", e);
        }
    }
}
