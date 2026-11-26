package za.gov.govaccess.ai.configuration;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import za.gov.govaccess.ai.enums.AiCacheMode;
import za.gov.govaccess.ai.enums.AiRedactionMode;

@ConfigurationProperties("govaccess.ai")
public record AiProperties(List<Provider> providers, Map<String, Integer> prompts,
                           AiCacheMode cacheMode, AiRedactionMode redactionMode, boolean killSwitch) {
    public AiProperties {
        providers = providers == null ? List.of() : List.copyOf(providers);
        prompts = prompts == null ? Map.of() : Map.copyOf(prompts);
        cacheMode = cacheMode == null ? AiCacheMode.OFF : cacheMode;
        redactionMode = redactionMode == null ? AiRedactionMode.BLOCK : redactionMode;
    }

    public record Provider(String name, String baseUrl, String apiKey, String model, boolean hosted) {
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank() && model != null && !model.isBlank();
        }
    }
}
