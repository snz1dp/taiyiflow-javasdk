package io.taiyiflow.sdk.core.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class CanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalJson() {
    }

    static String stringify(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(canonical(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Report payload is not serializable", exception);
        }
    }

    static byte[] utf8(JsonNode value) {
        return stringify(value).getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf8(value)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends JsonNode> T canonical(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) {
            return (T) (value == null ? MAPPER.nullNode() : value.deepCopy());
        }
        if (value.isArray()) {
            ArrayNode copy = MAPPER.createArrayNode();
            for (JsonNode item : value) {
                copy.add(canonical(item));
            }
            return (T) copy;
        }
        if (value.isObject()) {
            ObjectNode copy = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            for (String name : names) {
                copy.set(name, canonical(value.get(name)));
            }
            return (T) copy;
        }
        throw new IllegalArgumentException("Unsupported report payload node");
    }
}
