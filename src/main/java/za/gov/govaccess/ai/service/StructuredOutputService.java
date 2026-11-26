package za.gov.govaccess.ai.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Service;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.provider.FallbackAiProvider;

@Service
public class StructuredOutputService {
    private static final Pattern FENCE = Pattern.compile("\\A```(?:json)?[ \\t]*\\R(.*)\\R[ \\t]*```\\z",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final FallbackAiProvider provider;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public StructuredOutputService(FallbackAiProvider provider, ObjectMapper mapper, Validator validator) {
        this.provider = provider;
        this.mapper = mapper;
        this.validator = validator;
    }

    public <T> T generate(AiRequest request, Class<T> type) {
        JsonNode definition = request.jsonSchema();
        if (!type.isRecord() || definition == null || !definition.isObject()) {
            throw new IllegalArgumentException("Structured AI output requires a record and a JSON schema object");
        }
        requireLocalReferences(definition);
        JsonSchema schema = schemas.getSchema(definition);
        AiRequest structured = request.withContent(request.systemPrompt() + "\n\nReturn a JSON object.",
                request.userContent(), request.attachments());
        try {
            return provider.completeValidated(structured, response -> validate(response.rawText(), type, schema));
        } catch (AiOutputInvalidException invalid) {
            if (provider.replayOnly()) {
                throw invalid;
            }
            var repair = structured.withContent(structured.systemPrompt(),
                    structured.userContent() + "\n\nReturn a corrected JSON response. Validation errors:\n"
                            + String.join("\n", invalid.errors()), structured.attachments());
            return provider.completeRepair(repair, structured, response -> validate(response.rawText(), type, schema));
        }
    }

    private static void requireLocalReferences(JsonNode node) {
        if (node.isObject()) {
            for (String keyword : List.of("$ref", "$dynamicRef", "$recursiveRef")) {
                if (node.has(keyword) && !node.get(keyword).asText().startsWith("#")) {
                    throw new IllegalArgumentException("AI schemas must use local references");
                }
            }
            if (node.has("$schema") && !List.of("https://json-schema.org/draft/2020-12/schema",
                    "http://json-schema.org/draft-07/schema#", "https://json-schema.org/draft/2019-09/schema")
                    .contains(node.get("$schema").asText())) {
                throw new IllegalArgumentException("Unsupported AI schema dialect");
            }
        }
        node.forEach(StructuredOutputService::requireLocalReferences);
    }

    private <T> T validate(String raw, Class<T> type, JsonSchema schema) {
        try {
            String json = raw.strip();
            var fenced = FENCE.matcher(json);
            if (fenced.matches()) {
                json = fenced.group(1);
            }
            JsonNode tree = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(json);
            if (tree == null || !tree.isObject()) {
                throw invalid("Expected a JSON object");
            }
            var errors = new ArrayList<String>();
            // Schema locations and rule names come from the contract, without echoing model output.
            schema.validate(tree).forEach(error ->
                    errors.add("JSON Schema " + error.getType() + " at " + error.getSchemaLocation()));
            if (!errors.isEmpty()) {
                throw new AiOutputInvalidException(errors.stream().distinct().sorted().toList());
            }
            T value = mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).readValue(tree);
            var violations = validator.validate(value);
            if (!violations.isEmpty()) {
                throw new AiOutputInvalidException(violations.stream()
                        .map(v -> "Bean Validation " + v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()
                                + " on " + propertyNames(v.getPropertyPath())).sorted().toList());
            }
            return value;
        } catch (IOException e) {
            throw invalid("Malformed JSON or incompatible record fields");
        }
    }

    private static AiOutputInvalidException invalid(String error) {
        return new AiOutputInvalidException(List.of(error));
    }

    private static String propertyNames(Path path) {
        return StreamSupport.stream(path.spliterator(), false).map(Path.Node::getName)
                .filter(Objects::nonNull).collect(Collectors.joining("."));
    }
}
