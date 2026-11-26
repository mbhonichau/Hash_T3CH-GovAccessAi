package za.gov.govaccess.audit.service;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

public final class CanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private CanonicalJson() {}

    public static String document(String json) {
        try {
            var tree = MAPPER.readTree(json);
            if (tree == null || !tree.isObject()) {
                throw new IllegalArgumentException("Canonical content must be a JSON object");
            }
            return write(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid policy JSON", e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(normalize(MAPPER.valueToTree(value)));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot canonicalize JSON", e);
        }
    }

    public static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, JsonNode>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), normalize(entry.getValue())));
            return MAPPER.createObjectNode().setAll(sorted);
        }
        if (node.isArray()) {
            var array = MAPPER.createArrayNode();
            node.forEach(item -> array.add(normalize(item)));
            return array;
        }
        return node.isNumber() ? DecimalNode.valueOf(node.decimalValue().stripTrailingZeros()) : node;
    }
}
