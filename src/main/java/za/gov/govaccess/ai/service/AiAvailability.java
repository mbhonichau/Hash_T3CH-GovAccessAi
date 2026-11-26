package za.gov.govaccess.ai.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import za.gov.govaccess.ai.configuration.AiProperties;

@Component
public class AiAvailability {
    private final boolean configured;
    private final AtomicBoolean runtime = new AtomicBoolean(true);

    public AiAvailability(AiProperties properties) {
        configured = properties.enabled() && !properties.killSwitch();
    }

    public boolean enabled() {
        return configured && runtime.get();
    }

    public boolean setEnabled(boolean enabled) {
        runtime.set(enabled);
        return enabled();
    }
}
