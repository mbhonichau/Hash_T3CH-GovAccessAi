package za.gov.govaccess.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hashtech.govaccessai.GovaccessaiApplication;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;
import za.gov.govaccess.ai.configuration.AiProperties;
import za.gov.govaccess.ai.configuration.AiProperties.Provider;
import za.gov.govaccess.ai.dto.AiAttachment;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.entity.AiCallLog;
import za.gov.govaccess.ai.enums.AiCacheMode;
import za.gov.govaccess.ai.enums.AiOutcome;
import za.gov.govaccess.ai.enums.AiRedactionMode;
import za.gov.govaccess.ai.provider.AiProviderException;
import za.gov.govaccess.ai.provider.FallbackAiProvider;
import za.gov.govaccess.ai.repository.AiCacheRepository;
import za.gov.govaccess.ai.repository.AiCallLogRepository;
import za.gov.govaccess.ai.repository.AiRequestLogRepository;
import za.gov.govaccess.ai.entity.AiRequestLog;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.repository.PromptRepository;
import za.gov.govaccess.ai.service.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static za.gov.govaccess.ai.enums.AiOutcome.*;

@SpringBootTest(classes = GovaccessaiApplication.class, properties = {
        "spring.profiles.active=test",
        "spring.datasource.url=jdbc:h2:mem:ai-foundation;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password="
})
class AiFoundationTests {
    @Autowired org.springframework.core.env.Environment environment;

    @Test
    void usesFlywayAndValidatesEntitiesByDefault() {
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    private static final String SCHEMA = """
            {"type":"object","properties":{"draft":{"type":"string"}},"required":["draft"],
             "additionalProperties":false}
            """;

    record Draft(@NotBlank String draft) {}

    @Autowired ObjectMapper mapper;
    @Autowired Validator validator;
    @Autowired RestClient.Builder builder;
    @Autowired AiResponseCache cache;
    @Autowired AiAuditService audit;
    @Autowired AiCacheRepository cacheRows;
    @Autowired AiCallLogRepository auditRows;
    @Autowired AiRequestLogRepository requestRows;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        auditRows.deleteAll();
        requestRows.deleteAll();
        cacheRows.deleteAll();
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void close() throws Exception {
        server.close();
    }

    @Test
    void enforcesBothUserBudgetsBeforeContactingTheProvider() throws Exception {
        long now = java.time.Instant.now().getEpochSecond();
        for (boolean daily : List.of(false, true)) {
            var identity = java.util.UUID.randomUUID();
            jdbc.update("INSERT INTO govaccess.ai_user_quota VALUES (?,?,?,?,?)",
                    identity, now / 60, now / 86400, daily ? 0 : 60, daily ? 500 : 0);
            var request = new AiRequest("draft", 1, "System", "Draft", null, mapper.readTree(SCHEMA),
                    0, true, new AiCallContext(identity, null, null));
            assertThatThrownBy(() -> service(AiCacheMode.OFF, false, local(server, "local")).generate(request, Draft.class))
                    .isInstanceOf(za.gov.govaccess.ai.provider.AiRateLimitException.class)
                    .hasMessageContaining("request limit");
        }
        assertThat(server.getRequestCount()).isZero();
        logicalOutcomes(RATE_LIMITED, RATE_LIMITED);
        assertThat(auditRows.count()).isZero();
    }

    @Test
    void recordsSyntheticResponsesAndReplaysTheValidatedCompletion() throws Exception {
        String prompt = "synthetic-recording-test";
        jdbc.update("DELETE FROM govaccess.ai_demo_response WHERE prompt_id=?", prompt);
        var request = new AiRequest(prompt, 1, "System", "Draft", null, mapper.readTree(SCHEMA),
                0, true, new AiCallContext(null, null, null));
        reply(server, "invalid JSON");
        reply(server, "{\"draft\":\"Validated demo\"}");
        var provider = local(server, "local");
        assertThat(service(AiCacheMode.RECORD, false, provider).generate(request, Draft.class).draft())
                .isEqualTo("Validated demo");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM govaccess.ai_demo_response WHERE prompt_id=?", Long.class, prompt))
                .isEqualTo(2);
        assertThat(service(AiCacheMode.REPLAY_ONLY, false, provider).generate(request, Draft.class).draft())
                .isEqualTo("Validated demo");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void sendsSchemaAndTextAndImageAttachments() throws Exception {
        reply(server, "{\"draft\":\"Policy draft\"}");
        var request = request().withContent("System", "Draft", List.of(
                new AiAttachment("Attachment".getBytes(StandardCharsets.UTF_8), "text/plain"),
                new AiAttachment(new byte[]{1, 2, 3}, "image/png")));
        assertThat(service(AiCacheMode.OFF, false, local(server, "primary")).generate(request, Draft.class))
                .isEqualTo(new Draft("Policy draft"));
        var http = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(http.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(http.getHeader("Authorization")).isEqualTo("Bearer test-key");
        var body = mapper.readTree(http.getBody().readUtf8());
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(body.at("/messages/1/content/1/text").asText()).isEqualTo("Attachment");
        assertThat(body.at("/messages/1/content/2/image_url/url").asText()).isEqualTo("data:image/png;base64,AQID");
        outcomes(SUCCESS);
        assertThat(auditRows.findAll().getFirst().getOutputSha256())
                .isEqualTo(AiRequestHashes.output("{\"draft\":\"Policy draft\"}"));
    }

    @Test
    void honorsRetryAfterAndAuditsBothAttempts() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "1"));
        reply(server, "{\"draft\":\"Recovered\"}");
        long start = System.nanoTime();
        service(AiCacheMode.OFF, false, local(server, "primary")).generate(request(), Draft.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(server.getRequestCount()).isEqualTo(2);
        outcomes(PROVIDER_ERROR, RETRY_SUCCESS);
        logicalOutcomes(RETRY_SUCCESS);
        var requestRow = requestRows.findAll().getFirst();
        assertThat(requestRow.getAttemptCount()).isEqualTo(2);
        assertThat(auditRows.findAll()).allSatisfy(row -> assertThat(row.getRequestId()).isEqualTo(requestRow.getId()));
    }

    @Test
    void retriesServerErrors() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(503));
        reply(server, "{\"draft\":\"Recovered\"}");
        service(AiCacheMode.OFF, false, local(server, "primary")).generate(request(), Draft.class);
        outcomes(PROVIDER_ERROR, PROVIDER_ERROR, RETRY_SUCCESS);
    }

    @Test
    void switchesSchemaModeOnlyForSchemaRejection() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"Unsupported json_schema\"}"));
        reply(server, "{\"draft\":\"Recovered\"}");
        service(AiCacheMode.OFF, false, local(server, "primary")).generate(request(), Draft.class);
        server.takeRequest(1, TimeUnit.SECONDS);
        var second = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(mapper.readTree(second.getBody().readUtf8()).at("/response_format/type").asText())
                .isEqualTo("json_object");
        outcomes(PROVIDER_ERROR, RETRY_SUCCESS);
    }

    @Test
    void repairsMalformedJsonOnceAndAcceptsFences() throws Exception {
        reply(server, "{bad");
        reply(server, "```json\n{\"draft\":\"Repaired\"}\n```");
        var settings = local(server, "primary");
        var result = service(AiCacheMode.READ_WRITE, false, settings).generate(request(), Draft.class);
        assertThat(result.draft()).isEqualTo("Repaired");
        server.takeRequest(1, TimeUnit.SECONDS);
        var repair = mapper.readTree(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertThat(repair.at("/messages/1/content").asText()).contains("Validation errors", "Malformed JSON")
                .doesNotContain("{bad");
        assertThat(service(AiCacheMode.REPLAY_ONLY, false, settings).generate(request(), Draft.class)).isEqualTo(result);
        assertThat(server.getRequestCount()).isEqualTo(2);
        outcomes(INVALID_OUTPUT, RETRY_SUCCESS);
        logicalOutcomes(RETRY_SUCCESS, CACHE_HIT);
    }

    @Test
    void failsSafeAfterSchemaAndBeanValidationErrors() throws Exception {
        reply(server, "{\"draft\":12}");
        reply(server, "{\"draft\":\"\"}");
        var service = service(AiCacheMode.READ_WRITE, false, local(server, "primary"));
        var request = request();
        assertThatThrownBy(() -> service.generate(request, Draft.class))
                .isInstanceOfSatisfying(AiOutputInvalidException.class,
                        error -> assertThat(error.errors()).anyMatch(message -> message.contains("NotBlank")));
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(cacheRows.count()).isZero();
        outcomes(INVALID_OUTPUT, INVALID_OUTPUT);
    }

    @Test
    void fallsBackAfterExhaustingThreeRetries() throws Exception {
        try (var backup = new MockWebServer()) {
            backup.start();
            for (int i = 0; i < 4; i++) {
                server.enqueue(new MockResponse().setResponseCode(503));
            }
            reply(backup, "{\"draft\":\"Backup\"}");
            var service = service(AiCacheMode.OFF, false, local(server, "primary"), local(backup, "backup"));
            assertThat(service.generate(request(), Draft.class).draft()).isEqualTo("Backup");
            assertThat(server.getRequestCount()).isEqualTo(4);
            assertThat(backup.getRequestCount()).isEqualTo(1);
            outcomes(PROVIDER_ERROR, PROVIDER_ERROR, PROVIDER_ERROR, PROVIDER_ERROR, RETRY_SUCCESS);
        }
    }

    @Test
    void doesNotRetryAuthenticationOrUnrelatedBadRequests() throws Exception {
        for (int code : List.of(400, 401, 403)) {
            server.enqueue(new MockResponse().setResponseCode(code));
        }
        var service = service(AiCacheMode.OFF, false, local(server, "primary"));
        var request = request();
        for (int count = 1; count <= 3; count++) {
            int expectedStatus = List.of(400, 401, 403).get(count - 1);
            assertThatThrownBy(() -> service.generate(request, Draft.class))
                    .isInstanceOfSatisfying(AiProviderException.class,
                            failure -> assertThat(failure.status()).isEqualTo(expectedStatus));
            assertThat(server.getRequestCount()).isEqualTo(count);
        }
        outcomes(PROVIDER_ERROR, PROVIDER_ERROR, PROVIDER_ERROR);
    }

    @Test
    void cachesValidatedOutputAndReplaysWithoutNetwork() throws Exception {
        reply(server, "{\"draft\":\"Cached\"}");
        var settings = local(server, "primary");
        var request = request();
        var live = service(AiCacheMode.READ_WRITE, false, settings);
        assertThat(live.generate(request, Draft.class)).isEqualTo(live.generate(request, Draft.class));
        var replay = service(AiCacheMode.REPLAY_ONLY, false, settings);
        assertThat(replay.generate(request, Draft.class).draft()).isEqualTo("Cached");
        var miss = request.withContent("System", "Other input", List.of());
        assertThatThrownBy(() -> replay.generate(miss, Draft.class)).isInstanceOf(AiReplayMissException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
        outcomes(SUCCESS);
        logicalOutcomes(SUCCESS, CACHE_HIT, CACHE_HIT, PROVIDER_ERROR);
    }

    @Test
    void masksEachPiiTypeForHostedSyntheticInput() throws Exception {
        reply(server, "{\"draft\":\"Masked\"}");
        var base = request();
        var request = new AiRequest(base.promptId(), base.promptVersion(), "Email: example@example.test",
                "ID: 0000000000018. Phone: +27 82 123 4567. Bank account: 123456789",
                List.of(new AiAttachment("example@example.test".getBytes(StandardCharsets.UTF_8), "text/plain")),
                base.jsonSchema(), 0, true, null, null, null);
        var hosted = new Provider("hosted", server.url("/v1").toString(), "test-key", "test-model", true);
        service(AiCacheMode.OFF, false, hosted).generate(request, Draft.class);
        var sent = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertThat(sent).contains("[ID]", "[PHONE]", "[ACCOUNT]", "[EMAIL]")
                .doesNotContain("0000000000018", "+27 82 123 4567", "123456789", "example@example.test");
        outcomes(SUCCESS);
    }

    @Test
    void blocksNonSyntheticHostedInputAndRespectsKillSwitch() throws Exception {
        var hosted = new Provider("hosted", server.url("/").toString(), "test-key", "test-model", true);
        var request = request();
        assertThatThrownBy(() -> service(AiCacheMode.OFF, false, hosted).generate(request, Draft.class))
                .isInstanceOf(AiProviderException.class);
        var syntheticPii = new AiRequest("draft", 1, "System", "example@example.test", List.of(),
                request.jsonSchema(), 0, true, null, null, null);
        var config = new AiProperties(List.of(hosted), Map.of(), AiCacheMode.OFF, AiRedactionMode.BLOCK, false);
        var blocked = new FallbackAiProvider(config, builder, new RegexPiiRedactor(), cache, audit,
                new AiAvailability(config));
        assertThatThrownBy(() -> new StructuredOutputService(blocked, mapper, validator).generate(syntheticPii, Draft.class))
                .isInstanceOf(AiProviderException.class);
        assertThatThrownBy(() -> service(AiCacheMode.OFF, true, hosted).generate(request, Draft.class))
                .isInstanceOf(AiProviderException.class);
        assertThat(server.getRequestCount()).isZero();
        outcomes();
        logicalOutcomes(BLOCKED_PII, BLOCKED_PII, KILL_SWITCH);
    }

    @Test
    void fallsBackToLocalProviderForNonSyntheticPolicyInput() throws Exception {
        reply(server, "{\"draft\":\"Parsed locally\"}");
        var hosted = new Provider("hosted", server.url("/v1/").toString(), "test-key", "hosted-model", true);
        var local = new Provider("ollama-local", server.url("/v1/").toString(), "", "gemma3:4b", false);
        var properties = new AiProperties(List.of(hosted, local), Map.of(), AiCacheMode.OFF,
                AiRedactionMode.BLOCK, false);
        var provider = new FallbackAiProvider(properties, builder, new RegexPiiRedactor(), cache, audit,
                new AiAvailability(properties));

        var result = new StructuredOutputService(provider, mapper, validator).generate(request(), Draft.class);

        assertThat(result).isEqualTo(new Draft("Parsed locally"));
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(mapper.readTree(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8())
                .path("model").asText()).isEqualTo("gemma3:4b");
    }

    @Test
    void auditsAttributedProbeAndHonorsRuntimeSwitchWithoutRetries() throws Exception {
        var config = new AiProperties(List.of(local(server, "primary")), Map.of(), AiCacheMode.READ_WRITE,
                AiRedactionMode.BLOCK, false);
        var availability = new AiAvailability(config);
        var provider = new FallbackAiProvider(config, builder, new RegexPiiRedactor(), cache, audit, availability);
        var service = new StructuredOutputService(provider, mapper, validator);
        var context = new AiCallContext(java.util.UUID.randomUUID(), "AI_PROVIDER", java.util.UUID.randomUUID());
        var request = new AiRequest("provider-probe", 1, "System", "Probe", List.of(),
                mapper.readTree(SCHEMA), 0, true, context);
        assertThat(availability.setEnabled(false)).isFalse();
        assertThatThrownBy(() -> service.probe(request, Draft.class)).isInstanceOf(AiProviderException.class);
        assertThat(server.getRequestCount()).isZero();
        assertThat(availability.setEnabled(true)).isTrue();
        server.enqueue(new MockResponse().setResponseCode(503));
        assertThatThrownBy(() -> service.probe(request, Draft.class)).isInstanceOf(AiProviderException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
        reply(server, "{\"draft\":\"Ready\"}");
        assertThat(service.probe(request, Draft.class)).isEqualTo(new Draft("Ready"));
        assertThat(server.getRequestCount()).isEqualTo(2);
        var probe = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(mapper.readTree(probe.getBody().readUtf8()).path("max_completion_tokens").asInt()).isEqualTo(128);
        outcomes(PROVIDER_ERROR, SUCCESS);
        logicalOutcomes(KILL_SWITCH, PROVIDER_ERROR, SUCCESS);
        assertThat(requestRows.findAll()).allSatisfy(row -> {
            assertThat(row.getUserId()).isEqualTo(context.userId());
            assertThat(row.getRelatedEntityId()).isEqualTo(context.relatedEntityId());
        });
    }

    @Test
    void runtimeKillSwitchEndsAnOutstandingRetryDelay() throws Exception {
        var config = new AiProperties(List.of(local(server, "primary")), Map.of(), AiCacheMode.OFF,
                AiRedactionMode.BLOCK, false);
        var availability = new AiAvailability(config);
        var provider = new FallbackAiProvider(config, builder, new RegexPiiRedactor(), cache, audit, availability);
        var service = new StructuredOutputService(provider, mapper, validator);
        var request = request();
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "60"));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> service.generate(request, Draft.class));
            try {
                assertThat(server.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
                availability.setEnabled(false);
                assertThatThrownBy(() -> result.get(3, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(AiProviderException.class);
            } finally {
                result.cancel(true);
            }
        }
        assertThat(server.getRequestCount()).isEqualTo(1);
        outcomes(PROVIDER_ERROR);
        logicalOutcomes(KILL_SWITCH);
    }

    @Test
    void loadsActivePromptsAndRejectsMissingFiles() {
        assertThat(new PromptRepository(new AiProperties(List.of(), Map.of("test-draft", 2), null, null, false))
                .active("test-draft").text()).contains("fictional policy draft");
        assertThatThrownBy(() -> new PromptRepository(
                new AiProperties(List.of(), Map.of("test-draft", 1), null, null, false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prompts/test-draft/v1.md");
    }

    private void outcomes(AiOutcome... expected) {
        var rows = auditRows.findAll();
        assertThat(rows).extracting(AiCallLog::getOutcome).containsExactlyInAnyOrder(expected);
        assertThat(rows).allSatisfy(row -> assertThat(row.getInputSha256()).matches("[a-f0-9]{64}"));
    }

    private void logicalOutcomes(AiOutcome... expected) {
        var requests = requestRows.findAll();
        assertThat(requests).extracting(AiRequestLog::getOutcome).containsExactlyInAnyOrder(expected);
        assertThat(requests).extracting(AiRequestLog::getAuditEventId).doesNotContainNull().doesNotHaveDuplicates();
        var attempts = auditRows.findAll();
        requests.forEach(request -> assertThat(request.getAttemptCount())
                .isEqualTo(Math.toIntExact(attempts.stream()
                        .filter(attempt -> request.getId().equals(attempt.getRequestId())).count())));
    }

    private StructuredOutputService service(AiCacheMode mode, boolean killSwitch, Provider... targets) {
        var properties = new AiProperties(List.of(targets), Map.of(), mode, AiRedactionMode.MASK, killSwitch);
        var provider = new FallbackAiProvider(properties, builder, new RegexPiiRedactor(), cache, audit,
                new AiAvailability(properties));
        return new StructuredOutputService(provider, mapper, validator);
    }

    private static Provider local(MockWebServer server, String name) {
        return new Provider(name, server.url("/v1/").toString(), "test-key", "test-model", false);
    }

    private AiRequest request() throws Exception {
        return new AiRequest("draft", 1, "System", "Draft", null, mapper.readTree(SCHEMA), 0);
    }

    private void reply(MockWebServer target, String content) {
        var body = mapper.createObjectNode();
        body.put("model", "test-model");
        body.putArray("choices").addObject().putObject("message").put("content", content);
        body.putObject("usage").put("prompt_tokens", 12).put("completion_tokens", 4);
        target.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()));
    }
}
