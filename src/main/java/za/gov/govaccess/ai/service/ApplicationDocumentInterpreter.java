package za.gov.govaccess.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import za.gov.govaccess.ai.dto.AiAttachment;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.repository.PromptRepository;
import za.gov.govaccess.document.dto.ExtractedField;
import za.gov.govaccess.document.dto.ExtractedField.Validation;
import za.gov.govaccess.document.service.DocumentFieldPlan;
import za.gov.govaccess.document.service.DocumentFieldPlan.Field;

@Service
public class ApplicationDocumentInterpreter {
    private static final Map<String, List<String>> TYPES = Map.of(
            "IDENTITY_DOCUMENT", List.of("identity document", "identity card", "identity number"),
            "PAYSLIP", List.of("payslip", "pay slip", "salary advice"),
            "BANK_STATEMENT", List.of("bank statement", "statement of account"),
            "PROOF_OF_RESIDENCE", List.of("proof of residence", "municipal account", "utility bill"),
            "ACADEMIC_TRANSCRIPT", List.of("academic transcript", "statement of results"),
            "ACCEPTANCE_LETTER", List.of("acceptance letter", "offer of admission"),
            "INCOME_DECLARATION", List.of("income declaration", "declaration of income"),
            "UNEMPLOYMENT_DECLARATION", List.of("unemployment declaration", "declaration of unemployment"));
    private static final Pattern IDENTIFIER = Pattern.compile("(?<![0-9])[0-9]{13}(?![0-9])");
    private final StructuredOutputService ai;
    private final PromptRepository prompts;
    private final DocumentFieldPlan plan;
    private final ObjectMapper mapper;

    public ApplicationDocumentInterpreter(StructuredOutputService ai, PromptRepository prompts,
                                          DocumentFieldPlan plan, ObjectMapper mapper) {
        this.ai = ai;
        this.prompts = prompts;
        this.plan = plan;
        this.mapper = mapper;
    }

    public record Classification(String documentType) {}
    public record Output(boolean documentTypeMismatch, @NotNull Map<String, @Valid Candidate> fields) {}
    public static final class DocumentTypeMismatch extends RuntimeException {}
    public record Candidate(JsonNode value, @DecimalMin("0") @DecimalMax("1") double confidence,
                            String sourceSnippet, Integer pageNumber) {}

    public String classify(List<String> pages, AiCallContext context) {
        var schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("documentType");
        var type = schema.putObject("properties").putObject("documentType");
        var values = type.putArray("enum");
        TYPES.keySet().stream().sorted().forEach(values::add);
        values.addNull();
        return ai.generate(request("document-classification", "", String.join("\n", signals(pages)), schema, List.of(), context),
                Classification.class).documentType();
    }

    public Map<String, ExtractedField> extract(String expectedType, Map<String, Field> fields, List<String> pages,
                                               AiCallContext context, int documentVersion,
                                               List<AiAttachment> attachments) {
        var inputs = new LinkedHashMap<String, Map<Integer, List<String>>>();
        fields.forEach((key, field) -> inputs.put(key, passages(pages, key, field)));
        var schema = plan.schema(fields);
        AiPromptBuilder.allowOnly(inputs.keySet(), schema.path("properties").path("fields").path("properties")
                .properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        var document = mapper.createObjectNode();
        document.set("typeIndicators", mapper.valueToTree(signals(pages)));
        document.set("fieldPassagesByPage", mapper.valueToTree(inputs));
        var result = ai.generate(request("document-extraction", "Expected document type: " + expectedType, document.toString(),
                schema, attachments, context), Output.class, output -> {
            output.fields().forEach((key, candidate) -> {
                if (candidate.value() == null || candidate.value().isNull()) {
                    if (candidate.confidence() != 0 || candidate.sourceSnippet() != null || candidate.pageNumber() != null) {
                        throw new AiOutputInvalidException(List.of("Missing evidence requires zero confidence and no citation"));
                    }
                    return;
                }
                String snippet = candidate.sourceSnippet();
                if (output.documentTypeMismatch()) {
                    throw new AiOutputInvalidException(List.of("A mismatched document requires all field values to be null"));
                }
                if (snippet == null || snippet.isBlank()
                        || inputs.get(key).getOrDefault(candidate.pageNumber(), List.of()).stream().noneMatch(s -> s.contains(snippet))
                        || IDENTIFIER.matcher(candidate.value().asText()).find()
                        || !supports(snippet, candidate.value())) {
                    throw new AiOutputInvalidException(List.of("Provide a citation supporting the selected field value"));
                }
            });
        });
        if (result.documentTypeMismatch()) throw new DocumentTypeMismatch();
        var extracted = new LinkedHashMap<String, ExtractedField>();
        fields.forEach((key, field) -> {
            var candidate = result.fields().get(key);
            var value = candidate.value() == null || candidate.value().isNull() ? null : candidate.value();
            extracted.put(key, new ExtractedField(value, candidate.confidence(), candidate.sourceSnippet(),
                    candidate.pageNumber(), context.relatedEntityId(), documentVersion, field.ruleCodes(),
                    validate(value, field, candidate.confidence()), false, false));
        });
        return extracted;
    }

    private static List<String> signals(List<String> pages) {
        var joined = String.join("\n", pages).toLowerCase(Locale.ROOT);
        return TYPES.values().stream().flatMap(List::stream).filter(joined::contains).sorted().toList();
    }

    private static boolean supports(String snippet, JsonNode value) {
        if (value.isBoolean()) return snippet.toLowerCase(Locale.ROOT).contains(value.asText());
        if (!value.isNumber()) return snippet.toLowerCase(Locale.ROOT).contains(value.asText().toLowerCase(Locale.ROOT));
        var numbers = Pattern.compile("-?[0-9][0-9, ]*(?:\\.[0-9]+)?").matcher(snippet);
        while (numbers.find()) {
            try {
                if (new BigDecimal(numbers.group().replace(",", "").strip().replace(" ", ""))
                        .compareTo(value.decimalValue()) == 0) return true;
            } catch (NumberFormatException ignored) {
                // The match is not a complete number.
            }
        }
        return false;
    }

    public static Map<Integer, List<String>> passages(List<String> pages, String key, Field field) {
        var result = new LinkedHashMap<Integer, List<String>>();
        String label = field.label().strip().toLowerCase(Locale.ROOT);
        for (int i = 0; i < pages.size(); i++) {
            var lines = pages.get(i).lines().map(String::strip).filter(line -> {
                String lower = line.toLowerCase(Locale.ROOT);
                if (!(lower.startsWith(label + ":") || lower.startsWith(key.toLowerCase(Locale.ROOT) + ":"))
                        || line.length() > 200 || IDENTIFIER.matcher(line).find()) return false;
                String value = line.substring(line.indexOf(':') + 1).strip();
                return switch (field.type()) {
                    case BOOLEAN -> value.matches("(?i)true|false");
                    case DATE -> value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}");
                    case INTEGER, DECIMAL, MONEY -> value.matches("(?i)(?:R|ZAR)?\\s*-?[0-9][0-9, ]*(?:\\.[0-9]+)?");
                    case TEXT -> !value.isBlank();
                };
            }).limit(20).toList();
            if (!lines.isEmpty()) result.put(i + 1, lines);
        }
        return result;
    }

    public static Validation validate(JsonNode value, Field field, double confidence) {
        if (value == null || value.isNull()) return Validation.WARNING;
        try {
            if (field.type() == za.gov.govaccess.rules.enums.FieldValueType.DATE
                    && (!value.isTextual() || LocalDate.parse(value.asText()).isAfter(LocalDate.now()))) return Validation.FAILED;
            if (field.type() == za.gov.govaccess.rules.enums.FieldValueType.MONEY
                    && (!value.isNumber() || value.decimalValue().signum() <= 0
                        || value.decimalValue().compareTo(new BigDecimal("1000000000")) > 0)) return Validation.FAILED;
            if (field.type() == za.gov.govaccess.rules.enums.FieldValueType.INTEGER && !value.isIntegralNumber()
                    || field.type() == za.gov.govaccess.rules.enums.FieldValueType.DECIMAL && !value.isNumber()
                    || field.type() == za.gov.govaccess.rules.enums.FieldValueType.BOOLEAN && !value.isBoolean()
                    || field.type() == za.gov.govaccess.rules.enums.FieldValueType.TEXT && !value.isTextual()
                    || value.asText().length() > 200 || IDENTIFIER.matcher(value.asText()).find()) return Validation.FAILED;
            if (field.unit() != null && value.isNumber() && List.of("days", "months", "years").contains(field.unit())) {
                int max = switch (field.unit()) { case "days" -> 366; case "months" -> 120; default -> 120; };
                if (value.decimalValue().signum() <= 0 || value.decimalValue().compareTo(BigDecimal.valueOf(max)) > 0) {
                    return Validation.FAILED;
                }
            }
        } catch (java.time.DateTimeException e) {
            return Validation.FAILED;
        }
        return confidence < 0.6 ? Validation.WARNING : Validation.PASSED;
    }

    private AiRequest request(String id, String prefix, String content, JsonNode schema, List<AiAttachment> attachments,
                              AiCallContext context) {
        var prompt = prompts.active(id);
        return AiPromptBuilder.build(prompt, AiPromptBuilder.Data.DOCUMENT, prefix, content, attachments, schema, false, context);
    }
}
