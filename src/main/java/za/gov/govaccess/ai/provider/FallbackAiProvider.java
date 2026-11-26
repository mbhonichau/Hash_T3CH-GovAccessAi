package za.gov.govaccess.ai.provider;

import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import za.gov.govaccess.ai.configuration.AiProperties;
import za.gov.govaccess.ai.configuration.AiProperties.Provider;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.dto.AiResponse;
import za.gov.govaccess.ai.enums.AiCacheMode;
import za.gov.govaccess.ai.enums.AiOutcome;
import za.gov.govaccess.ai.service.*;

@Component
public class FallbackAiProvider implements AiProvider {
    private record Target(Provider settings, OpenAiCompatibleProvider client) {}

    private final List<Target> targets;
    private final AiProperties properties;
    private final PiiRedactor redactor;
    private final AiResponseCache cache;
    private final AiAuditService audit;

    public FallbackAiProvider(AiProperties properties, RestClient.Builder builder, PiiRedactor redactor,
                              AiResponseCache cache, AiAuditService audit) {
        this.properties = properties;
        this.redactor = redactor;
        this.cache = cache;
        this.audit = audit;
        this.targets = properties.providers().stream()
                .filter(p -> p.configured() || properties.cacheMode() == AiCacheMode.REPLAY_ONLY
                        && p.model() != null && !p.model().isBlank())
                .map(p -> new Target(p, properties.cacheMode() == AiCacheMode.REPLAY_ONLY
                        ? null : new OpenAiCompatibleProvider(p, builder)))
                .toList();
    }

    @Override
    public AiResponse complete(AiRequest request) {
        return execute(request, null, Function.identity(), false);
    }

    public <T> T completeValidated(AiRequest request, Function<AiResponse, T> validate) {
        return execute(request, null, validate, true);
    }

    public <T> T completeRepair(AiRequest request, AiRequest original, Function<AiResponse, T> validate) {
        return execute(request, original, validate, true);
    }

    public boolean replayOnly() {
        return properties.cacheMode() == AiCacheMode.REPLAY_ONLY;
    }

    private <T> T execute(AiRequest original, AiRequest cacheIdentity, Function<AiResponse, T> validate, boolean cacheable) {
        if (properties.killSwitch()) {
            audit.log(original, event(null, null, 0), AiOutcome.KILL_SWITCH);
            throw new AiProviderException("AI calls are disabled");
        }

        boolean failedAttempt = cacheIdentity != null;
        boolean blocked = false;
        for (Target target : targets) {
            Provider settings = target.settings();
            AiRequest request = settings.hosted() ? redactor.redact(original, properties.redactionMode()) : original;
            if (request == null || settings.hosted() && !original.synthetic()) {
                blocked = true;
                audit.log(original, event(settings, null, 0), AiOutcome.BLOCKED_PII);
                continue;
            }

            String key = AiRequestHashes.cacheKey(request, settings.model());
            if (cacheable && cacheIdentity == null && properties.cacheMode() != AiCacheMode.OFF) {
                var cached = cache.get(key);
                if (cached.isPresent()) {
                    return validated(request, cached.get(), validate, AiOutcome.CACHE_HIT);
                }
            }
            if (properties.cacheMode() == AiCacheMode.REPLAY_ONLY) {
                audit.log(request, event(settings, null, 0), AiOutcome.PROVIDER_ERROR);
                continue;
            }

            AiResponse response;
            int[] failures = {0};
            try {
                response = target.client().complete(request, (latency, body) -> {
                    failures[0]++;
                    audit.log(request, event(settings, body, latency), AiOutcome.PROVIDER_ERROR);
                });
            } catch (AiProviderException e) {
                if (failures[0] == 0) {
                    audit.log(request, event(settings, null, 0), AiOutcome.PROVIDER_ERROR);
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                failedAttempt = true;
                continue;
            }
            T result = validated(request, response, validate,
                    failedAttempt || failures[0] > 0 ? AiOutcome.RETRY_SUCCESS : AiOutcome.SUCCESS);
            if (cacheable && properties.cacheMode() == AiCacheMode.READ_WRITE) {
                AiRequest identity = cacheIdentity == null ? request : settings.hosted()
                        ? redactor.redact(cacheIdentity, properties.redactionMode()) : cacheIdentity;
                if (identity != null) {
                    cache.put(AiRequestHashes.cacheKey(identity, settings.model()), response);
                }
            }
            return result;
        }
        if (targets.isEmpty()) {
            audit.log(original, event(null, null, 0), AiOutcome.PROVIDER_ERROR);
        }
        if (properties.cacheMode() == AiCacheMode.REPLAY_ONLY) {
            throw new AiReplayMissException();
        }
        throw new AiProviderException(blocked && !failedAttempt
                ? "Hosted AI input was blocked" : "No AI provider completed the request");
    }

    private <T> T validated(AiRequest request, AiResponse response, Function<AiResponse, T> validate, AiOutcome outcome) {
        T value;
        try {
            value = validate.apply(response);
        } catch (RuntimeException e) {
            audit.log(request, response, AiOutcome.INVALID_OUTPUT);
            throw e;
        }
        audit.log(request, response, outcome);
        return value;
    }

    private static AiResponse event(Provider provider, String body, long latency) {
        return new AiResponse(body, provider == null ? null : provider.model(),
                provider == null ? null : provider.name(), latency, null, null);
    }
}
