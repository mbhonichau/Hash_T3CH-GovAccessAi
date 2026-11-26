package za.gov.govaccess.ai.provider;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import za.gov.govaccess.ai.provider.AiProviderException.Reason;
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
    private final AiAvailability availability;

    public FallbackAiProvider(AiProperties properties, RestClient.Builder builder, PiiRedactor redactor,
                              AiResponseCache cache, AiAuditService audit, AiAvailability availability) {
        this.properties = properties;
        this.redactor = redactor;
        this.cache = cache;
        this.audit = audit;
        this.availability = availability;
        this.targets = properties.providers().stream()
                .filter(p -> p.configured() || properties.cacheMode() == AiCacheMode.REPLAY_ONLY
                        && p.model() != null && !p.model().isBlank())
                .map(p -> new Target(p, properties.cacheMode() == AiCacheMode.REPLAY_ONLY
                        ? null : new OpenAiCompatibleProvider(p, builder)))
                .toList();
    }

    @Override
    public AiResponse complete(AiRequest request) {
        return inRequest(request, () -> execute(request, null, Function.identity(), false, false));
    }

    public <T> T completeValidated(AiRequest request, Function<AiResponse, T> validate) {
        return inRequest(request, () -> execute(request, null, validate, true, false));
    }

    public <T> T completeRepair(AiRequest request, AiRequest original, Function<AiResponse, T> validate) {
        return inRequest(original, () -> execute(request, original, validate, true, false));
    }

    public <T> T probe(AiRequest request, Function<AiResponse, T> validate) {
        return inRequest(request, () -> execute(request, null, validate, false, true));
    }

    public <T> T inRequest(AiRequest request, Supplier<T> operation) {
        return audit.around(request, operation);
    }

    public boolean replayOnly() {
        return properties.cacheMode() == AiCacheMode.REPLAY_ONLY;
    }

    private <T> T execute(AiRequest original, AiRequest cacheIdentity, Function<AiResponse, T> validate,
                          boolean cacheable, boolean probe) {
        if (!availability.enabled()) {
            audit.logicalOutcome(event(null, null, 0), AiOutcome.KILL_SWITCH);
            throw new AiProviderException(Reason.DISABLED, "AI calls are disabled");
        }

        boolean failedAttempt = cacheIdentity != null;
        boolean blocked = false;
        AiProviderException lastFailure = null;
        for (Target target : targets) {
            Provider settings = target.settings();
            AiRequest request = settings.hosted() ? redactor.redact(original, properties.redactionMode()) : original;
            if (request == null || settings.hosted() && !original.synthetic()) {
                blocked = true;
                if (lastFailure == null) {
                    audit.logicalOutcome(event(settings, null, 0), AiOutcome.BLOCKED_PII);
                }
                continue;
            }

            String key = AiRequestHashes.cacheKey(request, settings.model());
            if (cacheable && cacheIdentity == null && properties.cacheMode() != AiCacheMode.OFF) {
                var cached = cache.get(key);
                if (cached.isPresent()) {
                    return validated(request, cached.get(), validate, AiOutcome.CACHE_HIT, false);
                }
            }
            if (properties.cacheMode() == AiCacheMode.REPLAY_ONLY) {
                audit.logicalOutcome(event(settings, null, 0), AiOutcome.PROVIDER_ERROR);
                continue;
            }

            AiResponse response;
            int[] failures = {0};
            try {
                response = target.client().complete(request, (latency, body) -> {
                    failures[0]++;
                    audit.log(request, event(settings, body, latency), AiOutcome.PROVIDER_ERROR);
                }, probe, availability::enabled);
            } catch (AiProviderException e) {
                if (e.reason() == Reason.DISABLED) {
                    audit.logicalOutcome(event(settings, null, 0), AiOutcome.KILL_SWITCH);
                    throw e;
                }
                if (failures[0] == 0) {
                    audit.logicalOutcome(event(settings, null, 0), AiOutcome.PROVIDER_ERROR);
                }
                if (probe || Thread.currentThread().isInterrupted()) {
                    throw e;
                }
                failedAttempt = true;
                lastFailure = e;
                continue;
            }
            T result = validated(request, response, validate,
                    failedAttempt || failures[0] > 0 ? AiOutcome.RETRY_SUCCESS : AiOutcome.SUCCESS, true);
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
            audit.logicalOutcome(event(null, null, 0), AiOutcome.PROVIDER_ERROR);
        }
        if (properties.cacheMode() == AiCacheMode.REPLAY_ONLY) {
            throw new AiReplayMissException();
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        if (blocked) {
            throw new AiProviderException(Reason.BLOCKED, "Hosted AI input was blocked");
        }
        throw new AiProviderException(Reason.NOT_CONFIGURED, "No AI provider configured");
    }

    private <T> T validated(AiRequest request, AiResponse response, Function<AiResponse, T> validate,
                            AiOutcome outcome, boolean attempt) {
        T value;
        try {
            value = validate.apply(response);
        } catch (RuntimeException e) {
            recordResult(request, response, AiOutcome.INVALID_OUTPUT, attempt);
            throw e;
        }
        recordResult(request, response, outcome, attempt);
        return value;
    }

    private void recordResult(AiRequest request, AiResponse response, AiOutcome outcome, boolean attempt) {
        if (attempt) {
            audit.log(request, response, outcome);
        } else {
            audit.logicalOutcome(response, outcome);
        }
    }

    private static AiResponse event(Provider provider, String body, long latency) {
        return new AiResponse(body, provider == null ? null : provider.model(),
                provider == null ? null : provider.name(), latency, null, null);
    }
}
