package za.gov.govaccess.ai.configuration;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import za.gov.govaccess.ai.enums.AiCacheMode;

@Component
public class AiHealthIndicator implements HealthIndicator {
    private final AiProperties properties;

    public AiHealthIndicator(AiProperties properties) {
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (properties.killSwitch()) {
            return Health.unknown().withDetail("reason", "AI disabled").build();
        }
        if (properties.cacheMode() == AiCacheMode.REPLAY_ONLY) {
            return Health.up().withDetail("mode", "REPLAY_ONLY").build();
        }
        boolean configured = properties.providers().stream().anyMatch(AiProperties.Provider::configured);
        return configured ? Health.up().withDetail("providerConfiguration", "present").build()
                : Health.unknown().withDetail("reason", "No AI provider configured").build();
    }
}
