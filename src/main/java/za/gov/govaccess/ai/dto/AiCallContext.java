package za.gov.govaccess.ai.dto;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;
import za.gov.govaccess.security.ActorIdentity;

public record AiCallContext(UUID userId, String relatedEntityType, UUID relatedEntityId, UUID applicationId) {
    public AiCallContext(UUID userId, String relatedEntityType, UUID relatedEntityId) {
        this(userId, relatedEntityType, relatedEntityId, null);
    }
    public static AiCallContext forPrincipal(Principal principal, String entityType, UUID entityId) {
        return new AiCallContext(ActorIdentity.of(Objects.requireNonNull(principal)).id(), entityType, entityId);
    }
}
