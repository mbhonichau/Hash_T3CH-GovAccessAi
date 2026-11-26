package za.gov.govaccess.ai.configuration;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import za.gov.govaccess.ai.enums.AiCacheMode;
import za.gov.govaccess.ai.enums.AiRedactionMode;

@ConfigurationProperties("govaccess.ai")
public record AiProperties(List<Provider> providers, Map<String, Integer> prompts,
                           AiCacheMode cacheMode, AiRedactionMode redactionMode, boolean killSwitch, Boolean enabled) {
    @ConstructorBinding
    public AiProperties {
        providers = providers == null ? List.of() : List.copyOf(providers);
        prompts = prompts == null ? Map.of() : Map.copyOf(prompts);
        cacheMode = cacheMode == null ? AiCacheMode.OFF : cacheMode;
        redactionMode = redactionMode == null ? AiRedactionMode.BLOCK : redactionMode;
        enabled = enabled == null ? Boolean.TRUE : enabled;
    }

    public AiProperties(List<Provider> providers, Map<String, Integer> prompts,
                        AiCacheMode cacheMode, AiRedactionMode redactionMode, boolean killSwitch) {
        this(providers, prompts, cacheMode, redactionMode, killSwitch, true);
    }

    public record Provider(String name, String baseUrl, String apiKey, String model, boolean hosted) {
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank() && model != null && !model.isBlank();
        }
    }
}
