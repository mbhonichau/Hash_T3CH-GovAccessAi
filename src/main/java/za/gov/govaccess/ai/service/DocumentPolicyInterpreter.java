package za.gov.govaccess.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntConsumer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.enums.AiRedactionMode;
import za.gov.govaccess.ai.repository.PromptRepository;
import za.gov.govaccess.document.service.PolicySourceReader.Chunk;
import za.gov.govaccess.policy.dto.PolicyImportReview;
import za.gov.govaccess.policy.dto.PolicyImportReview.RejectedRule;
import za.gov.govaccess.policy.dto.PolicyImportReview.RuleReviewNote;
import za.gov.govaccess.rules.entity.DocumentType;
import za.gov.govaccess.rules.enums.RuleOperator;
import za.gov.govaccess.rules.model.RuleDocument;
import za.gov.govaccess.rules.model.RuleDocument.Rule;
import za.gov.govaccess.rules.service.RuleCatalogueService;
import za.gov.govaccess.rules.service.RuleDocumentValidator;

@Component
public class DocumentPolicyInterpreter {
    private static final BigDecimal AMBIGUITY_CONFIDENCE_CAP = new BigDecimal("0.59");
    private static final Set<String> PROTECTED_FIELD_TOKENS = Set.of("age", "area", "race", "gender", "sex",
            "religion", "ethnicity", "disability", "language", "nationality", "citizenship", "demographics", "name");
    private final StructuredOutputService outputs;
    private final PromptRepository.Prompt prompt;
    private final RuleCatalogueService catalogue;
    private final RuleDocumentValidator validator;
    private final PiiRedactor redactor;
    private final ObjectMapper mapper;
    private final JsonNode schema;

    public DocumentPolicyInterpreter(StructuredOutputService outputs, PromptRepository prompts,
                                     RuleCatalogueService catalogue, RuleDocumentValidator validator,
                                     PiiRedactor redactor, ObjectMapper mapper) throws IOException {
        this.outputs = outputs;
        this.prompt = prompts.version("policy-interpretation", 4);
        this.catalogue = catalogue;
        this.validator = validator;
        this.redactor = redactor;
        this.mapper = mapper;
        try (var input = new ClassPathResource("prompts/policy-interpretation/document-schema-v4.json").getInputStream()) {
            schema = mapper.readTree(input);
        }
    }

    public record ChunkOutput(int schemaVersion, @NotNull List<JsonNode> rules, String effectiveFrom,
                              @NotNull List<String> exceptions, @NotNull List<String> definitions,
                              @NotNull List<String> requiredDocuments, @NotNull List<String> unmappedRequirements,
                              @NotNull List<String> ambiguities, @NotNull List<String> exemptions,
                              @NotNull List<String> calculationMethods) {}

    public PolicyImportReview interpret(String sourceLabel, List<Chunk> chunks, AiCallContext context, IntConsumer progress) {
        var definitions = catalogue.catalogue();
        var merged = new Merge();
        String fields = catalogueText(definitions);
        for (int index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            var request = new AiRequest(prompt.id(), prompt.version(), prompt.text(),
                    fields + "\n<Page>" + chunk.page() + "</Page>\n<Section>" + chunk.section()
                            + "</Section>\n<policy>\n" + escapePolicyTags(chunk.text()) + "\n</policy>",
                    List.of(), schema, 0, false, context);
            // Uploaded sources are never attested synthetic. The hosted-provider gate stays in force.
            if (redactor.redact(request, AiRedactionMode.BLOCK) == null) {
                throw new IllegalArgumentException("The source contains an identifier. Redact it before importing.");
            }
            var output = outputs.generate(request, ChunkOutput.class);
            mergeMetadata(output, merged, definitions.documentTypes());
            for (var candidate : output.rules()) {
                accept(candidate, chunk, sourceLabel, definitions, merged);
            }
            progress.accept(index + 1);
        }
        return new PolicyImportReview(new RuleDocument(1, merged.rules, null), merged.effectiveFrom,
                List.copyOf(merged.exceptions), List.copyOf(merged.definitions), List.copyOf(merged.documents),
                List.copyOf(merged.unmapped), List.copyOf(merged.ambiguities), List.copyOf(merged.rejected),
                Map.copyOf(merged.confidence), List.copyOf(merged.exemptions), List.copyOf(merged.calculationMethods),
                Map.copyOf(merged.ruleNotes));
    }

    private static String escapePolicyTags(String text) {
        return text.replaceAll("(?i)<(/?policy)\\s*>", "&lt;$1&gt;");
    }

    private String catalogueText(RuleCatalogueService.Catalogue definitions) {
        var fields = mapper.createObjectNode();
        new TreeMap<>(definitions.fields()).forEach((key, field) -> {
            var details = fields.putObject(key).put("valueType", field.getValueType().name());
            details.put("unit", field.getUnit());
            var sources = details.putArray("sourceDocumentTypes");
            field.getSourceDocumentTypes().stream().map(DocumentType::getKey).sorted().forEach(sources::add);
        });
        return "<fields>" + fields + "</fields>\n<operators>"
                + Arrays.toString(RuleOperator.values()) + "</operators>\n<documentTypes>"
                + definitions.documentTypes().stream().sorted().toList() + "</documentTypes>";
    }

    private void accept(JsonNode candidate, Chunk chunk, String sourceLabel, RuleCatalogueService.Catalogue definitions,
                        Merge merged) {
        String code = candidate.path("ruleCode").asText("");
        var candidateConfidence = new LinkedHashMap<String, BigDecimal>();
        var candidateNotes = new LinkedHashMap<String, RuleReviewNote>();
        try {
            Rule rule = mapper.treeToValue(
                    canonicalCandidate(candidate, chunk, sourceLabel, candidateConfidence, candidateNotes), Rule.class);
            validator.validate(new RuleDocument(1, List.of(rule), null), definitions.fields(), definitions.documentTypes());
            var keys = new HashSet<String>();
            var semantics = new HashSet<String>();
            collect(rule, "", keys, semantics);
            if (keys.stream().anyMatch(merged.codes::contains) || semantics.stream().anyMatch(merged.semantics::contains)) {
                throw new IllegalArgumentException("Duplicate rule code or equivalent rule");
            }
            merged.rules.add(rule);
            merged.codes.addAll(keys);
            merged.semantics.addAll(semantics);
            merged.confidence.putAll(candidateConfidence);
            merged.ruleNotes.putAll(candidateNotes);
        } catch (IOException e) {
            merged.rejected.add(new RejectedRule(code, "Unknown operator or invalid rule structure", candidate));
        } catch (IllegalArgumentException e) {
            merged.rejected.add(new RejectedRule(code, e.getMessage(), candidate));
        }
    }

    private ObjectNode canonicalCandidate(JsonNode candidate, Chunk chunk, String document,
                                          Map<String, BigDecimal> confidence, Map<String, RuleReviewNote> notes) {
        if (!(candidate instanceof ObjectNode object) || !object.path("confidence").isNumber()
                || !object.path("source").isObject()) {
            throw new IllegalArgumentException("Invalid rule or source structure");
        }
        var copy = object.deepCopy();
        String code = copy.path("ruleCode").asText();
        var source = (ObjectNode) copy.get("source");
        if (source.path("page").asInt() != chunk.page() || !source.path("section").asText().equals(chunk.section())) {
            throw new IllegalArgumentException("Source page or heading does not match the supplied excerpt");
        }
        String quote = normalize(source.path("quote").asText());
        if (quote.isBlank() || !normalize(chunk.text()).contains(quote)) {
            throw new IllegalArgumentException("Source quote was not found in the supplied excerpt");
        }
        source.put("document", document);
        String ambiguity = copy.remove("ambiguityNote").asText(null);
        boolean review = copy.remove("requiresHumanReview").booleanValue()
                || protectedField(copy.path("fieldKey").asText(""))
                || protectedField(copy.path("condition").path("fieldKey").asText(""));
        var certainty = copy.remove("confidence").decimalValue();
        if (ambiguity != null && !ambiguity.isBlank()) {
            certainty = certainty.min(AMBIGUITY_CONFIDENCE_CAP);
            review = true;
        }
        confidence.put(code, certainty);
        if (review) {
            notes.put(code, new RuleReviewNote(ambiguity, true));
        }
        if (copy.path("group").isObject()) {
            var group = (ObjectNode) copy.get("group");
            var children = (com.fasterxml.jackson.databind.node.ArrayNode) group.get("rules");
            for (int i = 0; i < children.size(); i++) {
                children.set(i, canonicalCandidate(children.get(i), chunk, document, confidence, notes));
            }
        }
        return copy;
    }

    private static boolean protectedField(String key) {
        return Arrays.stream(key.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT)
                        .split("[^a-z0-9]+"))
                .anyMatch(PROTECTED_FIELD_TOKENS::contains);
    }

    private static String normalize(String text) {
        return text.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
                .replaceAll("(?U)\\s+", " ").strip();
    }

    private static void collect(Rule rule, String scope, Set<String> codes, Set<String> semantics) {
        if (!codes.add(rule.ruleCode())) {
            throw new IllegalArgumentException("Duplicate rule code");
        }
        String key = semanticKey(rule);
        if (!semantics.add(scope + key)) {
            throw new IllegalArgumentException("Duplicate equivalent rule");
        }
        rule.children().forEach(child -> collect(child, scope + "/" + key, codes, semantics));
    }

    private static String semanticKey(Rule rule) {
        String condition = rule.condition() == null ? "" : rule.condition().fieldKey()
                + "=" + scalar(rule.condition().expectedValue());
        if (rule.group() != null) {
            return List.of(rule.operator(), condition, rule.children().stream()
                    .map(DocumentPolicyInterpreter::semanticKey).sorted().toList()).toString();
        }
        var expected = rule.expectedValue();
        Object value = rule.operator() == RuleOperator.IN_SET && expected != null && expected.isArray()
                ? java.util.stream.StreamSupport.stream(expected.spliterator(), false)
                        .map(DocumentPolicyInterpreter::scalar).distinct().sorted().toList()
                : scalar(expected);
        return Arrays.asList(rule.fieldKey(), rule.operator(), value, condition).toString();
    }

    private static String scalar(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (node.isArray()) {
            return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                    .map(DocumentPolicyInterpreter::scalar).toList().toString();
        }
        return node.toString();
    }

    private static void mergeMetadata(ChunkOutput output, Merge merged, Set<String> documentTypes) {
        merged.exceptions.addAll(output.exceptions());
        merged.exemptions.addAll(output.exemptions());
        merged.calculationMethods.addAll(output.calculationMethods());
        merged.definitions.addAll(output.definitions());
        merged.unmapped.addAll(output.unmappedRequirements());
        merged.ambiguities.addAll(output.ambiguities());
        for (String document : output.requiredDocuments()) {
            if (documentTypes.contains(document)) {
                merged.documents.add(document);
            } else {
                merged.unmapped.add("Unmapped document type: " + document);
            }
        }
        if (output.effectiveFrom() != null) {
            try {
                var date = LocalDate.parse(output.effectiveFrom());
                if (merged.effectiveFrom == null) {
                    merged.effectiveFrom = date;
                } else if (!merged.effectiveFrom.equals(date)) {
                    merged.ambiguities.add("The excerpts report different effective dates.");
                }
            } catch (DateTimeParseException e) {
                merged.ambiguities.add("An excerpt reports an invalid effective date.");
            }
        }
    }

    private static final class Merge {
        private final List<Rule> rules = new ArrayList<>();
        private final Set<String> codes = new HashSet<>();
        private final Set<String> semantics = new HashSet<>();
        private final Set<String> exceptions = new LinkedHashSet<>();
        private final Set<String> exemptions = new LinkedHashSet<>();
        private final Set<String> calculationMethods = new LinkedHashSet<>();
        private final Set<String> definitions = new LinkedHashSet<>();
        private final Set<String> documents = new LinkedHashSet<>();
        private final Set<String> unmapped = new LinkedHashSet<>();
        private final Set<String> ambiguities = new LinkedHashSet<>();
        private final List<RejectedRule> rejected = new ArrayList<>();
        private final Map<String, BigDecimal> confidence = new LinkedHashMap<>();
        private final Map<String, RuleReviewNote> ruleNotes = new LinkedHashMap<>();
        private LocalDate effectiveFrom;
    }
}
