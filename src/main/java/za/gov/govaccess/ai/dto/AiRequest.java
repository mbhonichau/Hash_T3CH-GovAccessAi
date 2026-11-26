package za.gov.govaccess.ai.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record AiRequest(String promptId, int promptVersion, String systemPrompt, String userContent,
                        List<AiAttachment> attachments, JsonNode jsonSchema, double temperature,
                        boolean synthetic, AiCallContext context) {
    public AiRequest {
        Objects.requireNonNull(promptId);
        Objects.requireNonNull(systemPrompt);
        Objects.requireNonNull(userContent);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        jsonSchema = jsonSchema == null ? null : jsonSchema.deepCopy();
        context = context == null ? new AiCallContext(null, null, null) : context;
    }

    public AiRequest(String promptId, int promptVersion, String systemPrompt, String userContent,
                     List<AiAttachment> attachments, JsonNode jsonSchema, double temperature,
                     boolean synthetic, UUID userId, String relatedEntityType, UUID relatedEntityId) {
        this(promptId, promptVersion, systemPrompt, userContent, attachments, jsonSchema, temperature,
                synthetic, new AiCallContext(userId, relatedEntityType, relatedEntityId));
    }

    public AiRequest(String promptId, int promptVersion, String systemPrompt, String userContent,
                     List<AiAttachment> attachments, JsonNode jsonSchema, double temperature) {
        this(promptId, promptVersion, systemPrompt, userContent, attachments, jsonSchema, temperature,
                false, null, null, null);
    }

    @Override
    public JsonNode jsonSchema() {
        return jsonSchema == null ? null : jsonSchema.deepCopy();
    }

    public AiRequest withContent(String system, String user, List<AiAttachment> content) {
        return new AiRequest(promptId, promptVersion, system, user, content, jsonSchema, temperature,
                synthetic, context);
    }

    public UUID userId() { return context.userId(); }
    public String relatedEntityType() { return context.relatedEntityType(); }
    public UUID relatedEntityId() { return context.relatedEntityId(); }
}
