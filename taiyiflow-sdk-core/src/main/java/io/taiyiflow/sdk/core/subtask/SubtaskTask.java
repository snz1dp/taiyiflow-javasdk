package io.taiyiflow.sdk.core.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record SubtaskTask(String taskId, long version, ObjectNode payload) {
    public SubtaskTask {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        payload = payload == null ? null : payload.deepCopy();
    }

    public static SubtaskTask fromPlatform(JsonNode value) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException("Invalid collaboration task");
        String id = text(value, "id");
        if (id == null) id = text(value, "task_id");
        return new SubtaskTask(id, value.path("version").asLong(0), (ObjectNode) value);
    }

    public String status() {
        return payload == null ? null : text(payload, "status");
    }

    private static String text(JsonNode value, String field) {
        JsonNode item = value.get(field);
        return item != null && item.isTextual() && !item.asText().isBlank() ? item.asText() : null;
    }
}
