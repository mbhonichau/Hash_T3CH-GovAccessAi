package za.gov.govaccess.ai.dto;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

public record AiCallContext(UUID userId, String relatedEntityType, UUID relatedEntityId) {
    public static AiCallContext forPrincipal(Principal principal, String entityType, UUID entityId) {
        // The current in-memory Spring Security account has a username but no persisted user ID.
        String username = Objects.requireNonNull(principal).getName();
        UUID userId = UUID.nameUUIDFromBytes(("govaccess:principal:" + username).getBytes(StandardCharsets.UTF_8));
        return new AiCallContext(userId, entityType, entityId);
    }
}
