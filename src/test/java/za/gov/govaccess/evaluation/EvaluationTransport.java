package za.gov.govaccess.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.mockito.Mockito;
import org.springframework.beans.BeanUtils;
import org.springframework.web.client.RestClient;
import za.gov.govaccess.ai.configuration.AiProperties.Provider;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.dto.AiResponse;
import za.gov.govaccess.ai.enums.AiRedactionMode;
import za.gov.govaccess.ai.provider.FallbackAiProvider;
import za.gov.govaccess.ai.provider.OpenAiCompatibleProvider;
import za.gov.govaccess.ai.service.AiOutputInvalidException;
import za.gov.govaccess.ai.service.AiPromptBuilder;
import za.gov.govaccess.ai.service.RegexPiiRedactor;
import za.gov.govaccess.audit.service.CanonicalJson;

final class EvaluationTransport {
    enum Mode { STUB, REPLAY, LIVE }

    record Attempt(String fixture, String prompt, int version, int sequence, boolean repair,
                   boolean schemaValid, long latencyMs, JsonNode parsed) {}

    private final ObjectMapper mapper;
    private final Mode mode;
    private final ObjectNode recording;
    private final JsonNode replay;
    private final OpenAiCompatibleProvider live;
    private final long delayMs;
    private final List<Attempt> attempts = new ArrayList<>();
    private final Map<String, Integer> sequences = new HashMap<>();
    private final Map<String, Integer> versions = new TreeMap<>();
    private final String provider;
    private final String model;
    private String fixture;
    private Map<String, List<String>> scripted = Map.of();
    private long nextLiveCall;

    EvaluationTransport(ObjectMapper mapper, Mode mode) throws Exception {
        this.mapper = mapper;
        this.mode = mode;
        delayMs = Long.getLong("ai.eval.delay-ms", 3000);
        if (mode == Mode.LIVE && delayMs < 0) throw new IllegalArgumentException("Delay must be nonnegative");
        if (mode == Mode.LIVE) {
            model = requireEnv("AI_MODEL");
            provider = "openai-compatible";
            var settings = new Provider(provider, requireEnv("AI_BASE_URL"), System.getenv("AI_API_KEY"), model, true);
            live = BeanUtils.instantiateClass(OpenAiCompatibleProvider.class
                    .getDeclaredConstructor(Provider.class, RestClient.Builder.class), settings, RestClient.builder());
        } else {
            live = null;
            model = "scripted-fixture";
            provider = "stub";
        }
        if (mode == Mode.REPLAY) {
            String file = System.getProperty("ai.eval.replay");
            try (var input = file == null ? EvaluationTransport.class.getResourceAsStream("/evaluation/replay.json")
                    : Files.newInputStream(Path.of(file))) {
                if (input == null) throw new IllegalArgumentException("Missing evaluation recording");
                replay = mapper.readTree(input);
            }
            if (!"DEMO DATA ONLY".equals(replay.path("label").asText()) || !replay.path("responses").isObject()) {
                throw new IllegalArgumentException("Invalid evaluation recording");
            }
        } else {
            replay = null;
        }
        recording = mapper.createObjectNode().put("label", "DEMO DATA ONLY")
                .put("provider", provider).put("model", model);
        recording.putObject("responses");
    }

    FallbackAiProvider boundary() {
        return Mockito.mock(FallbackAiProvider.class, call -> switch (call.getMethod().getName()) {
            case "inRequest" -> ((Supplier<?>) call.getArgument(1)).get();
            // Evaluation replay supplies every raw attempt, including the original invalid response.
            case "replayOnly" -> false;
            case "completeValidated", "completeRepair" -> {
                boolean repair = call.getMethod().getName().equals("completeRepair");
                yield complete(call.getArgument(0), call.getArgument(repair ? 2 : 1), repair);
            }
            default -> throw new UnsupportedOperationException(call.getMethod().getName());
        });
    }

    void start(String fixture, Map<String, List<String>> scripted) {
        this.fixture = fixture;
        this.scripted = scripted;
        sequences.clear();
    }

    private Object complete(AiRequest request, Function<AiResponse, ?> validate, boolean repair) throws Exception {
        int sequence = sequences.merge(request.promptId(), 1, Integer::sum);
        versions.put(request.promptId(), request.promptVersion());
        String key = fixture + "/" + request.promptId() + "/v" + request.promptVersion() + "/" + sequence;
        String fingerprint = CanonicalJson.sha256(CanonicalJson.write(List.of(request.systemPrompt(),
                request.userContent(), request.jsonSchema(), request.temperature(), request.attachments())));
        long start = System.nanoTime();
        String raw = null;
        JsonNode parsed = null;
        boolean valid = false;
        try {
            raw = switch (mode) {
                case STUB -> {
                    var responses = scripted.getOrDefault(request.promptId(), List.of());
                    if (sequence > responses.size()) throw new IllegalStateException("Unexpected stub attempt " + key);
                    yield responses.get(sequence - 1);
                }
                case REPLAY -> {
                    var entry = replay.path("responses").path(key);
                    if (!entry.path("raw").isTextual() || !entry.path("requestSha256").asText().equals(fingerprint)) {
                        throw new IllegalStateException("Replay miss or changed prompt inputs for " + key);
                    }
                    yield entry.path("raw").textValue();
                }
                case LIVE -> live(request);
            };
            if (raw.length() <= 200_000) {
                try {
                    parsed = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                            com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                            .readTree(raw.strip().replaceFirst("(?is)^\\x60{3}(?:json)?\\s*(.*?)\\s*\\x60{3}$", "$1"));
                    valid = parsed != null && parsed.isObject()
                            && JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                            .getSchema(request.jsonSchema()).validate(parsed).isEmpty();
                } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                    valid = false;
                }
            }
            return validate.apply(new AiResponse(raw, model(), provider(), 0, null, null));
        } finally {
            attempts.add(new Attempt(fixture, request.promptId(), request.promptVersion(), sequence, repair, valid,
                    (System.nanoTime() - start) / 1_000_000, parsed));
            if (raw != null && raw.length() <= 200_000) {
                ((ObjectNode) recording.get("responses")).putObject(key).put("requestSha256", fingerprint).put("raw", raw);
            }
        }
    }

    private String live(AiRequest request) throws InterruptedException {
        var synthetic = new AiRequest(request.promptId(), request.promptVersion(), request.systemPrompt(),
                request.userContent(), request.attachments(), request.jsonSchema(), request.temperature(), true, request.context());
        var outbound = AiPromptBuilder.forProvider(synthetic, true, AiRedactionMode.BLOCK, new RegexPiiRedactor());
        if (outbound == null) throw new AiOutputInvalidException(List.of("Fixture blocked before live transport"));
        long millis = (nextLiveCall - System.nanoTime()) / 1_000_000;
        if (millis > 0) Thread.sleep(millis);
        try {
            return live.complete(outbound).rawText();
        } finally {
            nextLiveCall = System.nanoTime() + delayMs * 1_000_000;
        }
    }

    List<Attempt> attempts() { return List.copyOf(attempts); }
    Map<String, Integer> versions() { return Map.copyOf(versions); }
    ObjectNode recording() { return recording; }
    String provider() { return replay == null ? provider : replay.path("provider").asText(); }
    String model() { return replay == null ? model : replay.path("model").asText(); }

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required in LIVE");
        return value;
    }
}
