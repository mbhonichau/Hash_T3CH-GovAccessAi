package za.gov.govaccess.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import za.gov.govaccess.ai.dto.AiAttachment;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.enums.AiRedactionMode;
import za.gov.govaccess.ai.repository.PromptRepository.Prompt;

public final class AiPromptBuilder {
    public enum Data { POLICY, DOCUMENT, ASSESSMENT }
    private static final int MAX_TEXT = 200_000;

    private AiPromptBuilder() {}

    public static AiRequest build(Prompt prompt, Data boundary, String instructions, String data,
                                  List<AiAttachment> attachments, JsonNode schema, boolean synthetic, AiCallContext context) {
        String content = boundary == null ? data : (instructions.isBlank() ? "" : instructions + "\n")
                + tagged(boundary, data, boundary == Data.POLICY);
        if (content.length() > MAX_TEXT) throw new IllegalArgumentException("AI input exceeds the maximum text length.");
        return new AiRequest(prompt.id(), prompt.version(), prompt.text(), content, attachments, schema, 0, synthetic, context);
    }

    public static void allowOnly(Collection<String> supplied, Set<String> allowed) {
        if (!allowed.containsAll(supplied)) throw new IllegalArgumentException("An AI prompt field is outside the task allowlist.");
    }

    public static void allowOnly(JsonNode supplied, Set<String> allowed) {
        if (!supplied.isObject()) throw new IllegalArgumentException("Expected structured AI prompt data.");
        allowOnly(supplied.properties().stream().map(java.util.Map.Entry::getKey).toList(), allowed);
    }

    public static String tagged(Data boundary, String content, boolean plainText) {
        String tag = boundary.name().toLowerCase(java.util.Locale.ROOT);
        String escaped = plainText ? Pattern.compile("(?i)<(/?" + tag + ")\\s*>").matcher(content)
                .replaceAll("&lt;$1&gt;") : content.replace("<", "\\u003c").replace(">", "\\u003e");
        return "<" + tag + ">\n" + escaped + "\n</" + tag + ">";
    }

    public static AiRequest forProvider(AiRequest request, boolean hosted, AiRedactionMode mode, PiiRedactor redactor) {
        return hosted ? redactor.redact(request, mode) : request;
    }

    static AiRequest structured(AiRequest request) {
        return request.withContent(request.systemPrompt() + "\n\nReturn a JSON object.", request.userContent(), request.attachments());
    }

    static AiRequest repair(AiRequest request, List<String> errors) {
        return request.withContent(request.systemPrompt(), request.userContent()
                + "\n\nReturn a corrected JSON response. Validation errors:\n" + String.join("\n", errors), request.attachments());
    }
}
