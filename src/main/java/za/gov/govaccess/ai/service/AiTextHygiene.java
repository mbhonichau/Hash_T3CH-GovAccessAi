package za.gov.govaccess.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

final class AiTextHygiene {
    private AiTextHygiene() {}

    static JsonNode clean(JsonNode node) {
        if (node.isObject()) {
            var object = (ObjectNode) node;
            object.properties().forEach(entry -> object.set(entry.getKey(), clean(entry.getValue())));
        } else if (node.isArray()) {
            var array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) array.set(index, clean(array.get(index)));
        } else if (node.isTextual()) {
            return TextNode.valueOf(node.textValue()
                    .replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", "")
                    .replaceAll("(?s)<!--.*?-->|</?[A-Za-z][^>]*>", "")
                    .replaceAll("!?\\[([^\\]\\r\\n]*)]\\([^\\s)]*\\)", "$1")
                    .replaceAll("(?i)\\b(?:https?://|www\\.)[^\\s<>]+", "")
                    .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", ""));
        }
        return node;
    }
}
