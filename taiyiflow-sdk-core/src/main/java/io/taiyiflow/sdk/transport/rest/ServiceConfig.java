package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.transport.envelope.JsonNodes;

import java.util.List;

public final class ServiceConfig {
    private final JsonNode raw;

    ServiceConfig(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new IllegalArgumentException("/api/v1/config must return a JSON object");
        }
        this.raw = raw.deepCopy();
    }

    public String streamProtocol() { return text("stream_protocol"); }
    public String defaultFlowId() { return requiredText("default_flow_id"); }
    public String documentFlowId() { return requiredText("doc_unstructured_flow_id"); }
    public String imageFlowId() { return requiredText("image_flow_id"); }
    public String audioFlowId() { return requiredText("audio_flow_id"); }
    public String videoFlowId() { return requiredText("video_flow_id"); }

    public List<String> documentExtensions() {
        JsonNode values = raw.get("doc_exts");
        if (values == null || !values.isArray()) {
            return List.of();
        }
        return JsonNodes.stream(values).filter(JsonNode::isTextual).map(JsonNode::asText)
                .map(value -> value.trim().toLowerCase(java.util.Locale.ROOT))
                .map(value -> value.startsWith(".") ? value.substring(1) : value)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    public long maxFileSizeUpload() { return positiveLong("max_file_size_upload", Long.MAX_VALUE); }
    public long maxFileSizeBytes() {
        long megabytes = maxFileSizeUpload();
        return megabytes > Long.MAX_VALUE / (1024L * 1024L) ? Long.MAX_VALUE : megabytes * 1024L * 1024L;
    }
    public int onceMaxFileCount() {
        long value = positiveLong("once_max_file_count", Integer.MAX_VALUE);
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    public JsonNode raw() { return raw.deepCopy(); }

    private String requiredText(String name) {
        String value = text(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Server configuration is missing required field: " + name);
        }
        return value.trim();
    }

    private String text(String name) {
        JsonNode value = raw.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private long positiveLong(String name, long fallback) {
        JsonNode value = raw.get(name);
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalStateException("Server configuration field must be positive: " + name);
        }
        return value.asLong();
    }
}
