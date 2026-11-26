package za.gov.govaccess.policy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.audit.service.BusinessAuditService;
import za.gov.govaccess.audit.service.BusinessAuditService.Change;
import za.gov.govaccess.audit.service.CanonicalJson;
import za.gov.govaccess.document.service.PolicySourceReader;
import za.gov.govaccess.policy.dto.PolicyImportJob;
import za.gov.govaccess.policy.dto.PolicyImportJob.Status;
import za.gov.govaccess.policy.dto.PolicyImportReview;
import za.gov.govaccess.policy.dto.PolicyImportReview.RuleReviewNote;
import za.gov.govaccess.policy.repository.PolicyImportRepository;
import za.gov.govaccess.policy.repository.PolicyImportRepository.Edit;
import za.gov.govaccess.policy.repository.PolicyImportRepository.Source;
import za.gov.govaccess.policy.repository.PolicyVersionRepository;
import za.gov.govaccess.programme.repository.ProgrammeRepository;
import za.gov.govaccess.rules.model.RuleDocument;
import za.gov.govaccess.rules.model.RuleDocument.Rule;
import za.gov.govaccess.security.ManagedUserService;

@Service
public class PolicyImportJobs {
    private static final BigDecimal LOW_CONFIDENCE = new BigDecimal("0.6");
    private final PolicyImportRepository jobs;
    private final ProgrammeRepository programmes;
    private final PolicyVersionRepository versions;
    private final RulePolicyService policies;
    private final ObjectMapper mapper;
    private final BusinessAuditService audit;
    private final ManagedUserService users;

    public PolicyImportJobs(PolicyImportRepository jobs, ProgrammeRepository programmes,
                            PolicyVersionRepository versions, RulePolicyService policies, ObjectMapper mapper,
                            BusinessAuditService audit, ManagedUserService users) {
        this.jobs = jobs;
        this.programmes = programmes;
        this.versions = versions;
        this.policies = policies;
        this.mapper = mapper;
        this.audit = audit;
        this.users = users;
    }

    public PolicyImportJob enqueue(Principal owner, String programme, String version, String title, Source source) {
        if (version == null || version.isBlank() || version.length() > 100
                || programmes.findByCode(programme).isEmpty()
                || versions.findByProgrammeCodeAndVersionLabel(programme, version.strip()).isPresent()) {
            throw new IllegalArgumentException("Choose a programme and a new draft label of up to 100 characters.");
        }
        if (title == null || title.isBlank() || title.length() > 255) {
            throw new IllegalArgumentException("Provide a source document title of up to 255 characters.");
        }
        if (source.pdf() == null && (source.text() == null || source.text().isBlank()
                || source.text().length() > PolicySourceReader.MAX_TEXT_LENGTH)
                || source.pdf() != null && (source.text() != null || source.pdf().length == 0
                || source.pdf().length > PolicySourceReader.MAX_PDF_BYTES)) {
            throw new IllegalArgumentException("Supply one PDF (up to 8 MB) or paste up to 200000 characters of policy text.");
        }
        var job = new PolicyImportJob(UUID.randomUUID(), userId(owner), UUID.randomUUID(), programme, version.strip(),
                title.strip(), Status.QUEUED, 0, 0, null, Instant.now());
        jobs.enqueue(job, source);
        return job;
    }

    public List<PolicyImportJob> recent(Principal owner) {
        return jobs.recent(userId(owner));
    }

    public PolicyImportJob owned(UUID id, Principal owner) {
        return jobs.owned(id, userId(owner), false).orElseThrow(NoSuchElementException::new);
    }

    @Transactional
    public void complete(PolicyImportJob job, PolicyImportReview review) {
        var draft = policies.saveImportedDraft(job.draftId(), job.programmeCode(), job.versionLabel(),
                review.document(), job.userId());
        jobs.succeed(job.id(), json(review));
        audit.append(new Change(null, job.userId(), users.roleOf(job.userId()), "POLICY_AI_DRAFT_CREATED",
                draft.getId(), hash(draft.getContent()), null, null, null, null, null, null));
    }

    public record ReviewRow(Rule rule, BigDecimal confidence, boolean lowConfidence, RuleReviewNote note,
                            Edit edit, int depth) {}

    public record Review(PolicyImportReview imported, List<ReviewRow> rows) {}

    @Transactional(readOnly = true)
    public Review review(UUID id, Principal owner) {
        var job = owned(id, owner);
        requireSucceeded(job);
        var imported = original(id);
        var draft = versions.findById(job.draftId()).orElseThrow(NoSuchElementException::new);
        var edits = new LinkedHashMap<String, Edit>();
        jobs.edits(id).forEach(edit -> edits.putIfAbsent(edit.ruleCode(), edit));
        List<ReviewRow> rows = new ArrayList<>();
        rows(policies.read(draft.getContent()).rules(), imported, edits, rows, 0);
        return new Review(imported, List.copyOf(rows));
    }

    @Transactional
    public void edit(UUID id, Principal owner, String code, String newValue) {
        UUID editor = userId(owner);
        var job = jobs.owned(id, editor, true).orElseThrow(NoSuchElementException::new);
        requireSucceeded(job);
        var draft = versions.lockById(job.draftId()).orElseThrow(NoSuchElementException::new);
        var rules = policies.read(draft.getContent());
        var existing = find(rules.rules(), code);
        var original = find(original(id).document().rules(), code);
        if (existing.group() != null) {
            throw new IllegalArgumentException("A group has no editable expected value.");
        }
        JsonNode value = readEditValue(newValue);
        if (value.equals(existing.expectedValue())) {
            return;
        }
        var revised = new RuleDocument(rules.schemaVersion(), replace(rules.rules(), code, value), rules.notice());
        policies.validate(revised);
        draft.reviseImportedDraft(policies.write(revised));
        jobs.addEdit(id, editor, new Edit(code, owner.getName(), json(original.expectedValue()),
                json(existing.expectedValue()), json(value)));
        audit.append(new Change(null, editor, users.roleOf(editor), "POLICY_IMPORTED_RULE_EDITED",
                draft.getId(), hash(draft.getContent()), null,
                new RuleValue(code, existing.expectedValue()), new RuleValue(code, value), null, null, null));
    }

    private record RuleValue(String ruleCode, JsonNode expectedValue) {}

    private static String hash(String content) {
        return CanonicalJson.sha256(CanonicalJson.document(content));
    }

    private JsonNode readEditValue(String value) {
        if (value == null || value.isBlank() || value.length() > 2_000) {
            throw new IllegalArgumentException("Enter a JSON value of up to 2000 characters.");
        }
        try {
            return mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Enter a valid JSON value.", e);
        }
    }

    private static Rule find(List<Rule> rules, String code) {
        for (var rule : rules) {
            if (rule.ruleCode().equals(code)) {
                return rule;
            }
            if (!rule.children().isEmpty()) {
                try {
                    return find(rule.children(), code);
                } catch (NoSuchElementException ignored) {
                    // Continue looking in sibling branches.
                }
            }
        }
        throw new NoSuchElementException();
    }

    private static List<Rule> replace(List<Rule> rules, String code, JsonNode value) {
        return rules.stream().map(rule -> {
            var group = rule.group() == null ? null : new RuleDocument.Group(
                    rule.group().type(), replace(rule.children(), code, value));
            return new Rule(rule.ruleCode(), rule.name(), rule.fieldKey(), rule.operator(),
                    rule.ruleCode().equals(code) ? value : rule.expectedValue(), rule.unit(), rule.mandatory(),
                    rule.requiredEvidence(), rule.onMissingEvidence(), rule.source(), group, rule.condition());
        }).toList();
    }

    private static void rows(List<Rule> rules, PolicyImportReview imported, LinkedHashMap<String, Edit> edits,
                             List<ReviewRow> result, int depth) {
        for (Rule rule : rules) {
            var confidence = imported.confidence().get(rule.ruleCode());
            result.add(new ReviewRow(rule, confidence, confidence != null && confidence.compareTo(LOW_CONFIDENCE) < 0,
                    imported.ruleNotes().get(rule.ruleCode()), edits.get(rule.ruleCode()), depth));
            rows(rule.children(), imported, edits, result, depth + 1);
        }
    }

    private PolicyImportReview original(UUID id) {
        try {
            return mapper.readerFor(PolicyImportReview.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(jobs.review(id));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read the import review", e);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize policy import data", e);
        }
    }

    private static void requireSucceeded(PolicyImportJob job) {
        if (job.status() != Status.SUCCEEDED) {
            throw new NoSuchElementException();
        }
    }

    private static UUID userId(Principal owner) {
        return AiCallContext.forPrincipal(owner, null, null).userId();
    }
}
