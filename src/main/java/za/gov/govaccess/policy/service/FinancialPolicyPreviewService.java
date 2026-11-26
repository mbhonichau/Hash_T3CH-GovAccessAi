package za.gov.govaccess.policy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.stereotype.Service;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.rules.dto.FinancialScreenFacts;
import za.gov.govaccess.rules.dto.FinancialScreenResult;
import za.gov.govaccess.rules.model.FinancialScreenPolicy;
import za.gov.govaccess.rules.service.FinancialScreenEvaluator;

@Service
public class FinancialPolicyPreviewService {
    private final PolicyVersionRepository policies;
    private final ObjectReader reader;
    private final FinancialScreenEvaluator evaluator;

    public FinancialPolicyPreviewService(PolicyVersionRepository policies, ObjectMapper mapper,
                                         FinancialScreenEvaluator evaluator) {
        this.policies = policies;
        this.reader = mapper.readerFor(FinancialScreenPolicy.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.evaluator = evaluator;
    }

    public FinancialScreenResult preview(String programmeCode, String versionLabel, FinancialScreenFacts facts) {
        var version = policies.findByProgrammeCodeAndVersionLabel(programmeCode, versionLabel)
                .orElseThrow(() -> new IllegalArgumentException("Policy version not found"));
        try {
            FinancialScreenPolicy policy = reader.readValue(version.getRuleJson());
            if (policy == null) {
                throw new IllegalStateException("Stored financial screening policy is empty");
            }
            return evaluator.evaluate(policy, facts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored financial screening policy is invalid", e);
        }
    }
}
