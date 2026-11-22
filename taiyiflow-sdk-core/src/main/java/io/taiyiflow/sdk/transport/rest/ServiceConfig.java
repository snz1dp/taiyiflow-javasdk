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

    /** Server subtask parallelism quota: null when absent/invalid/negative, else min(raw, 32); 0 disables run_subtask. */
    public Integer maxParallelismSubtask() {
        JsonNode value = raw.get("max_parallelism_subtask");
        if (value == null || !value.canConvertToInt() || value.asInt() < 0) {
            return null;
        }
        return Math.min(value.asInt(), 32);
    }

    /** Server session concurrency quota; default 3, explicit null falls back, 0 is preserved (blocks new chats). */
    public int clientMaxSessionConcurrency() {
        JsonNode value = raw.get("client_max_session_concurrency");
        if (value == null || value.isNull() || !value.canConvertToInt()) {
            return 3;
        }
        return value.asInt();
    }

    public Integer agentAutoLoopMaxRounds() {
        JsonNode value = raw.get("agent_auto_loop_max_rounds");
        return value != null && value.canConvertToInt() && value.asInt() >= 0 ? value.asInt() : null;
    }

    /** Server-authoritative input token budget; the SDK only exposes it, enforcement stays on the platform. */
    public Integer maxInputTokens() {
        JsonNode value = raw.get("max_input_tokens");
        return value != null && value.canConvertToInt() && value.asInt() > 0 ? value.asInt() : null;
    }

    public boolean ontologyEnabled() {
        JsonNode value = raw.get("ontology");
        return value != null && value.isBoolean() && value.booleanValue();
    }

    /**
     * Server license summary from /config ({@code start_time/end_time/user_limit/user_count/
     * terminal_limit/expired/licensed}); display and pre-warning only. Expiry is enforced at the
     * run/chat entries with HTTP 403, not here. Null when the server omits the license object.
     */
    public JsonNode license() {
        JsonNode value = raw.get("license");
        return value != null && value.isObject() ? value.deepCopy() : null;
    }

    /** True when /config reports the license expired or not yet started; false when absent. */
    public boolean licenseExpired() {
        JsonNode value = raw.get("license");
        return value != null && value.path("expired").asBoolean(false);
    }

    /** License end_time (ISO text) for pre-warning; null when absent or permanent. */
    public String licenseEndTime() {
        JsonNode value = raw.get("license");
        if (value == null) {
            return null;
        }
        JsonNode end = value.get("end_time");
        return end != null && end.isTextual() ? end.asText() : null;
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
