package za.gov.govaccess.policy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.policy.entity.PolicyVersion;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.programme.entity.Programme;
import za.gov.govaccess.programme.repository.ProgrammeRepository;
import za.gov.govaccess.rules.model.FinancialScreenPolicy;
import za.gov.govaccess.rules.service.FinancialRuleConverter;
import za.gov.govaccess.security.ActorIdentity;
import org.springframework.security.core.context.SecurityContextHolder;

@Service
public class NsfasDemoPolicyService {
    public static final String PROGRAMME_CODE = "NSFAS_BURSARY";
    public static final String VERSION_LABEL = "2025 financial screening";

    private static final UUID PROGRAMME_ID = UUID.fromString("e58c7032-b27d-4117-9885-63d830fd6453");
    private static final UUID VERSION_ID = UUID.fromString("3c72c8f9-080f-49c4-9a96-36092d0d9187");

    private final ProgrammeRepository programmes;
    private final PolicyVersionRepository versions;
    private final FinancialPolicyPreviewService preview;
    private final ObjectMapper mapper;

    public NsfasDemoPolicyService(ProgrammeRepository programmes, PolicyVersionRepository versions,
                                  FinancialPolicyPreviewService preview, ObjectMapper mapper) {
        this.programmes = programmes;
        this.versions = versions;
        this.preview = preview;
        this.mapper = mapper;
    }

    public Optional<PolicyVersion> findVersion() {
        return findVersion(VERSION_LABEL);
    }

    public Optional<PolicyVersion> findVersion(String label) {
        return versions.findByProgrammeCodeAndVersionLabel(PROGRAMME_CODE, label)
                .filter(version -> version.getRuleJson() != null);
    }

    public List<PolicyVersion> drafts() {
        return versions.findByProgrammeCodeAndStatusOrderByVersionLabel(PROGRAMME_CODE, "DRAFT").stream()
                .filter(version -> version.getRuleJson() != null).toList();
    }

    public FinancialScreenPolicy bundledRules() {
        return preview.readRules(readDraftJson());
    }

    @Transactional
    public void saveDraft(String label, FinancialScreenPolicy rules, boolean aiGenerated) {
        saveDraft(UUID.randomUUID(), label, rules, aiGenerated);
    }

    @Transactional
    public void saveDraft(UUID id, String label, FinancialScreenPolicy rules, boolean aiGenerated) {
        if (label == null || label.isBlank() || label.length() > 100
                || versions.findByProgrammeCodeAndVersionLabel(PROGRAMME_CODE, label).isPresent()) {
            throw new IllegalArgumentException("Choose a new policy version label of up to 100 characters.");
        }
        var programme = getOrCreateProgramme();
        try {
            String json = mapper.writeValueAsString(rules);
            var draft = aiGenerated ? PolicyVersion.aiDraft(id, programme, label, json)
                    : PolicyVersion.manualDraft(id, programme, label, json);
            draft.setContent(mapper.writeValueAsString(FinancialRuleConverter.convert(rules)));
            draft.recordAuthor(ActorIdentity.of(SecurityContextHolder.getContext().getAuthentication()).id());
            versions.saveAndFlush(draft);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot save financial screening rules", e);
        }
    }

    @Transactional
    public void loadDraft() {
        if (findVersion().isPresent()) {
            return;
        }
        var programme = getOrCreateProgramme();
        var version = PolicyVersion.manualDraft(VERSION_ID, programme, VERSION_LABEL, readDraftJson());
        version.recordAuthor(ActorIdentity.of(SecurityContextHolder.getContext().getAuthentication()).id());
        try {
            version.setContent(mapper.writeValueAsString(FinancialRuleConverter.convert(preview.readRules(version))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot save financial screening rules", e);
        }
        versions.saveAndFlush(version);
    }

    private Programme getOrCreateProgramme() {
        return programmes.findByCode(PROGRAMME_CODE)
                .orElseGet(() -> programmes.save(Programme.demo(PROGRAMME_ID, PROGRAMME_CODE, "NSFAS Bursary")));
    }

    private static String readDraftJson() {
        try (var input = new ClassPathResource("demo/nsfas-2025-financial-screen.json").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the bundled NSFAS policy draft", e);
        }
    }
}
