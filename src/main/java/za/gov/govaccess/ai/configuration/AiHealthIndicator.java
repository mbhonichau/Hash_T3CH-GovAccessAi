package za.gov.govaccess.ai.configuration;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import za.gov.govaccess.ai.enums.AiCacheMode;
import za.gov.govaccess.ai.service.AiAvailability;

@Component
public class AiHealthIndicator implements HealthIndicator {
    private final AiProperties properties;
    private final AiAvailability availability;

    public AiHealthIndicator(AiProperties properties, AiAvailability availability) {
        this.properties = properties;
        this.availability = availability;
    }

    @Override
    public Health health() {
        if (!availability.enabled()) {
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
