package za.gov.govaccess.ai.repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import za.gov.govaccess.ai.configuration.AiProperties;

@Repository
public class PromptRepository {
    public record Prompt(String id, int version, String text) {}

    private final Map<String, Prompt> active;

    public PromptRepository(AiProperties properties) {
        var prompts = new HashMap<String, Prompt>();
        properties.prompts().forEach((id, version) -> prompts.put(id, version(id, version)));
        active = Map.copyOf(prompts);
    }

    public Prompt version(String id, int version) {
        if (!id.matches("[a-zA-Z0-9_-]+") || version < 1) {
            throw new IllegalArgumentException("Invalid prompt identifier or version: " + id);
        }
        String path = "prompts/" + id + "/v" + version + ".md";
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new Prompt(id, version, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load configured prompt " + path, e);
        }
    }

    public Prompt active(String id) {
        Prompt prompt = active.get(id);
        if (prompt == null) {
            throw new IllegalArgumentException("No active prompt configured for " + id);
        }
        return prompt;
    }
}
