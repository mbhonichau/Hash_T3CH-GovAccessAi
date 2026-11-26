package za.gov.govaccess.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.repository.PromptRepository;
import za.gov.govaccess.policy.service.NsfasDemoPolicyService;
import za.gov.govaccess.rules.enums.VerifiedSassaGrant;
import za.gov.govaccess.rules.enums.RuleOperator;
import za.gov.govaccess.rules.model.FinancialScreenPolicy;
import za.gov.govaccess.rules.model.FinancialScreenPolicy.GrantRule;
import za.gov.govaccess.rules.model.FinancialScreenPolicy.IncomeRule;

@Service
public class PolicyInterpretationService {
    private final PromptRepository prompts;
    private final StructuredOutputService structured;
    private final NsfasDemoPolicyService policies;
    private final JsonNode schema;

    public PolicyInterpretationService(PromptRepository prompts, StructuredOutputService structured,
                                       NsfasDemoPolicyService policies, ObjectMapper mapper)
            throws JsonProcessingException {
        this.prompts = prompts;
        this.structured = structured;
        this.policies = policies;
        schema = mapper.readTree("""
                {"type":"object","additionalProperties":false,
                 "required":["qualifyingSassaGrant","standardIncome","grantOperator","incomeOperator"],
                 "properties":{
                   "qualifyingSassaGrant":{"type":"array","minItems":1,"uniqueItems":true,
                     "items":{"type":"string","enum":["FOSTER_CARE","CARE_DEPENDENCY","CHILD_SUPPORT"]}},
                   "standardIncome":{"type":"number","minimum":0},
                   "grantOperator":{"type":"string","enum":["%s"]},
                   "incomeOperator":{"type":"string","enum":["%s"]}
                 }}
                """.formatted(RuleOperator.IN_SET, RuleOperator.LESS_THAN_OR_EQUAL));
    }

    public FinancialScreenPolicy interpretBundledSample(AiCallContext context) {
        var prompt = prompts.active("policy-interpretation");
        var request = new AiRequest(prompt.id(), prompt.version(), prompt.text(), """
                <fields>
                qualifyingSassaGrant: enum list, operator %s.
                standardIncome: decimal, ZAR per year, operator %s.
                </fields>
                <policy>
                In this synthetic bursary scenario, the qualifying verified grants are
                Foster Care, Care Dependency, and Child Support.
                The standard annual gross household income cap is ZAR 350000, inclusive.
                </policy>
                """.formatted(RuleOperator.IN_SET, RuleOperator.LESS_THAN_OR_EQUAL),
                List.of(), schema, 0, true, context);
        var reference = policies.bundledRules();
        var grant = reference.qualifyingSassaGrant();
        var income = reference.standardIncome();
        var extraction = structured.generate(request, Extraction.class, candidate -> {
            if (candidate.grantOperator() != RuleOperator.IN_SET
                    || candidate.incomeOperator() != RuleOperator.LESS_THAN_OR_EQUAL
                    || !grant.value().equals(candidate.qualifyingSassaGrant())
                    || income.value().compareTo(candidate.standardIncome()) != 0) {
                throw new AiOutputInvalidException(List.of("Extracted values do not match the bundled policy excerpt."));
            }
        });
        return new FinancialScreenPolicy(
                new GrantRule(grant.ruleKey(), extraction.qualifyingSassaGrant(), grant.sourceSection(), grant.sourceQuote()),
                new IncomeRule(income.ruleKey(), extraction.standardIncome(), income.sourceSection(), income.sourceQuote()),
                reference.disabilityIncome());
    }

    public record Extraction(@NotEmpty Set<@NotNull VerifiedSassaGrant> qualifyingSassaGrant,
                             @NotNull @DecimalMin("0") BigDecimal standardIncome,
                             @NotNull RuleOperator grantOperator, @NotNull RuleOperator incomeOperator) {
        public Extraction(Set<VerifiedSassaGrant> qualifyingSassaGrant, BigDecimal standardIncome) {
            this(qualifyingSassaGrant, standardIncome, RuleOperator.IN_SET, RuleOperator.LESS_THAN_OR_EQUAL);
        }
    }
}
