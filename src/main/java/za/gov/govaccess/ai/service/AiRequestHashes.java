package za.gov.govaccess.ai.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import za.gov.govaccess.ai.dto.AiRequest;

public final class AiRequestHashes {
    private AiRequestHashes() {}

    public static String input(AiRequest request) {
        var digest = digest();
        add(digest, request.systemPrompt());
        add(digest, request.userContent());
        add(digest, Double.toString(request.temperature()));
        var schema = request.jsonSchema();
        add(digest, schema == null ? "" : schema.toString());
        for (var attachment : request.attachments()) {
            add(digest, attachment.mimeType());
            add(digest, HexFormat.of().formatHex(digest().digest(attachment.bytes())));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String cacheKey(AiRequest request, String model) {
        var digest = digest();
        add(digest, request.promptId());
        add(digest, Integer.toString(request.promptVersion()));
        add(digest, model);
        add(digest, input(request));
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String output(String output) {
        return output == null ? null : HexFormat.of().formatHex(digest().digest(output.getBytes(StandardCharsets.UTF_8)));
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
