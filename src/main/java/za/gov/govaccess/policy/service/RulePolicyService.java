package za.gov.govaccess.policy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.policy.entity.PolicyVersion;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.programme.entity.Programme;
import za.gov.govaccess.programme.repository.ProgrammeRepository;
import za.gov.govaccess.rules.dto.VerifiedRuleEvidence;
import za.gov.govaccess.rules.model.RuleDocument;
import za.gov.govaccess.rules.service.RuleCatalogueService;
import za.gov.govaccess.rules.service.RuleDocumentValidator;
import za.gov.govaccess.rules.service.RuleEvaluator;

@Service
public class RulePolicyService {
    private final ProgrammeRepository programmes;
    private final PolicyVersionRepository policies;
    private final RuleCatalogueService catalogue;
    private final RuleDocumentValidator validator;
    private final RuleEvaluator evaluator;
    private final ObjectMapper mapper;
    private final ObjectReader reader;

    public RulePolicyService(ProgrammeRepository programmes, PolicyVersionRepository policies,
                             RuleCatalogueService catalogue, RuleDocumentValidator validator,
                             RuleEvaluator evaluator, ObjectMapper mapper) {
        this.programmes = programmes;
        this.policies = policies;
        this.catalogue = catalogue;
        this.validator = validator;
        this.evaluator = evaluator;
        this.mapper = mapper;
        reader = mapper.readerFor(RuleDocument.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    @Transactional
    public Programme createProgramme(String code, String name) {
        if (code == null || !code.matches("[A-Za-z][A-Za-z0-9_-]{0,99}") || name == null
                || name.isBlank() || name.length() > 255 || programmes.findByCode(code).isPresent()) {
            throw new IllegalArgumentException("Choose a valid, unique programme code and a name");
        }
        return programmes.saveAndFlush(new Programme(UUID.randomUUID(), code, name));
    }

    @Transactional
    public PolicyVersion saveDraft(String programmeCode, String versionLabel, RuleDocument document) {
        if (versionLabel == null || versionLabel.isBlank() || versionLabel.length() > 100
                || policies.findByProgrammeCodeAndVersionLabel(programmeCode, versionLabel).isPresent()) {
            throw new IllegalArgumentException("Choose a unique policy version label");
        }
        var programme = programmes.findByCode(programmeCode)
                .orElseThrow(() -> new IllegalArgumentException("Programme not found"));
        var definitions = catalogue.catalogue();
        validator.validate(document, definitions.fields(), definitions.documentTypes());
        var version = PolicyVersion.manualDraft(UUID.randomUUID(), programme, versionLabel, null);
        version.setContent(write(document));
        return policies.saveAndFlush(version);
    }

    public RuleEvaluator.Result evaluate(RuleDocument document, List<VerifiedRuleEvidence> evidence) {
        var definitions = catalogue.catalogue();
        return evaluator.evaluate(document, definitions.fields(), definitions.documentTypes(), evidence);
    }

    public RuleDocument read(String content) {
        try {
            RuleDocument document = reader.readValue(content);
            if (document == null) {
                throw new IllegalArgumentException("A canonical rule document is required");
            }
            return document;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid canonical rule document", e);
        }
    }

    public String write(RuleDocument document) {
        try {
            return mapper.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize the canonical rule document", e);
        }
    }
}
