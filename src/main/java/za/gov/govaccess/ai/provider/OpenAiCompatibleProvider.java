package za.gov.govaccess.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import za.gov.govaccess.ai.configuration.AiProperties.Provider;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.dto.AiResponse;

public class OpenAiCompatibleProvider implements AiProvider {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private final Provider settings;
    private final RestClient client;

    OpenAiCompatibleProvider(Provider settings, RestClient.Builder builder) {
        this.settings = settings;
        URI base = URI.create(settings.baseUrl());
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || !List.of("http", "https").contains(base.getScheme())) {
            throw new IllegalArgumentException("Invalid AI provider base URL");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        factory.setReadTimeout(TIMEOUT);
        this.client = builder.clone().requestFactory(factory).baseUrl(settings.baseUrl().replaceAll("/+$", "")).build();
    }

    @Override
    public AiResponse complete(AiRequest request) {
        return complete(request, (latency, body) -> {});
    }

    AiResponse complete(AiRequest request, BiConsumer<Long, String> onFailure) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", settings.model());
        payload.put("temperature", request.temperature());
        payload.put("messages", List.of(Map.of("role", "system", "content", request.systemPrompt()),
                Map.of("role", "user", "content", userContent(request))));
        JsonNode schema = request.jsonSchema();
        boolean schemaMode = schema != null;
        if (schemaMode) {
            payload.put("response_format", Map.of("type", "json_schema",
                    "json_schema", Map.of("name", "govaccess_output", "schema", schema)));
        }
        int retries = 0;
        while (true) {
            long start = System.nanoTime();
            try {
                JsonNode body = client.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .headers(headers -> {
                            if (settings.apiKey() != null && !settings.apiKey().isBlank()) {
                                headers.setBearerAuth(settings.apiKey());
                            }
                        }).body(payload).retrieve().body(JsonNode.class);
                if (body == null) {
                    throw new RestClientException("Empty AI provider response");
                }
                JsonNode content = body.path("choices").path(0).path("message").path("content");
                if (!content.isTextual()) {
                    onFailure.accept(elapsed(start), body.toString());
                    throw new AiProviderException("Missing AI completion");
                }
                JsonNode usage = body.path("usage");
                return new AiResponse(content.textValue(), body.path("model").asText(settings.model()),
                        settings.name(), elapsed(start), tokenCount(usage, "prompt_tokens"),
                        tokenCount(usage, "completion_tokens"));
            } catch (RestClientResponseException e) {
                onFailure.accept(elapsed(start), e.getResponseBodyAsString());
                int status = e.getStatusCode().value();
                if (schemaMode && rejectsSchema(status, e.getResponseBodyAsString())) {
                    schemaMode = false;
                    payload.put("response_format", Map.of("type", "json_object"));
                    continue;
                }
                if ((status == 429 || status >= 500 && status < 600) && retries < 3) {
                    backoff(retries++, e.getResponseHeaders());
                    continue;
                }
                throw new AiProviderException("AI provider returned HTTP " + status);
            } catch (RestClientException e) {
                onFailure.accept(elapsed(start), null);
                throw new AiProviderException("AI provider response unavailable");
            }
        }
    }

    private Object userContent(AiRequest request) {
        if (request.attachments().isEmpty()) {
            return request.userContent();
        }
        var parts = new ArrayList<Map<String, ?>>();
        parts.add(Map.of("type", "text", "text", request.userContent()));
        for (var attachment : request.attachments()) {
            String mime = attachment.mimeType().toLowerCase(Locale.ROOT);
            if (mime.startsWith("text/")) {
                parts.add(Map.of("type", "text", "text", new String(attachment.bytes(), StandardCharsets.UTF_8)));
            } else if (mime.matches("image/[a-z0-9.+-]+")) {
                String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(attachment.bytes());
                parts.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
            } else {
                throw new AiProviderException("Unsupported AI attachment media type");
            }
        }
        return parts;
    }

    private static boolean rejectsSchema(int status, String body) {
        String message = body.toLowerCase(Locale.ROOT);
        return (status == 400 || status == 422)
                && (message.contains("json_schema") || message.contains("response_format"));
    }

    private static void backoff(int retry, HttpHeaders headers) {
        long delay = (250L << retry) + ThreadLocalRandom.current().nextLong(100);
        String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter != null) {
            delay = Math.max(delay, retryAfterMillis(retryAfter.strip()));
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("AI provider retry interrupted");
        }
    }

    private static long retryAfterMillis(String value) {
        try {
            return Duration.ofSeconds(Long.parseLong(value)).toMillis();
        } catch (NumberFormatException | ArithmeticException e) {
            try {
                return Duration.between(java.time.Instant.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
            } catch (java.time.DateTimeException | ArithmeticException malformed) {
                return 0;
            }
        }
    }

    private static Integer tokenCount(JsonNode usage, String field) {
        JsonNode value = usage.path(field);
        return value.isIntegralNumber() && value.canConvertToInt() ? value.intValue() : null;
    }

    private static long elapsed(long start) {
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }
}
