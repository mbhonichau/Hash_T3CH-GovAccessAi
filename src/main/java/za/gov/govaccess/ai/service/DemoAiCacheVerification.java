package za.gov.govaccess.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import za.gov.govaccess.ai.configuration.AiProperties;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.enums.AiCacheMode;

@Component
class DemoAiCacheVerification implements ApplicationRunner {
    private final Map<String, DemoCase> cases;
    private final AiProperties properties;
    private final AiResponseCache cache;
    private final PiiRedactor redactor;
    private final ConfigurableApplicationContext application;
    private final boolean prewarm;

    private record DemoCase(Function<AiCallContext, AiRequest> request, Consumer<AiCallContext> run) {}

    DemoAiCacheVerification(PolicyInterpretationService interpretation, AiProperties properties, AiResponseCache cache,
                            PiiRedactor redactor, ConfigurableApplicationContext application,
                            @Value("${govaccess.ai.prewarm:false}") boolean prewarm) {
        cases = Map.of("bundled-nsfas-policy-import", new DemoCase(interpretation::bundledRequest,
                context -> interpretation.interpretBundledSample(context)));
        this.properties = properties;
        this.cache = cache;
        this.redactor = redactor;
        this.application = application;
        this.prewarm = prewarm;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean demo = application.getEnvironment().acceptsProfiles(Profiles.of("demo"));
        if (!prewarm && !demo) return;
        if (prewarm) {
            if (properties.cacheMode() != AiCacheMode.RECORD) throw new IllegalStateException("Demo prewarming requires RECORD mode.");
            cases.values().forEach(item -> item.run().accept(new AiCallContext(null, null, null)));
        }
        List<String> missing = missingCases();
        var log = LoggerFactory.getLogger(getClass());
        if (missing.isEmpty()) log.info("AI replay cache contains all registered demo cases.");
        else log.warn("AI replay cache is missing demo cases: {}", missing);
        if (prewarm) {
            if (!missing.isEmpty()) throw new IllegalStateException("Demo prewarming did not populate all registered cases.");
            application.close();
        }
    }

    List<String> missingCases() {
        var missing = new ArrayList<String>();
        cases.forEach((name, item) -> {
            var request = AiPromptBuilder.structured(item.request().apply(new AiCallContext(null, null, null)));
            boolean found = properties.providers().stream().filter(provider -> provider.model() != null && !provider.model().isBlank())
                    .anyMatch(provider -> {
                        var screened = AiPromptBuilder.forProvider(request, provider.hosted(), properties.redactionMode(), redactor);
                        return screened != null && cache.get(AiRequestHashes.cacheKey(screened, provider.model())).isPresent();
                    });
            if (!found) missing.add(name);
        });
        return List.copyOf(missing);
    }
}
