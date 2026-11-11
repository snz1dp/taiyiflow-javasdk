package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

public class CollaborationTaskClient {
    private static final String BASE = "/api/v1/agents/client/collaborations";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient rest;

    public CollaborationTaskClient(RestClient rest) {
        this.rest = rest;
    }

    public JsonNode get(String taskId) throws Exception {
        return rest.get(BASE + "/" + RestClient.pathSegment(taskId));
    }

    public JsonNode list(Map<String, ?> filters) throws Exception {
        return rest.get(RestClient.appendQuery(BASE, filters));
    }

    public JsonNode running(String taskId, JsonNode progress) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode().put("status", "running");
        if (progress != null && !progress.isNull()) {
            payload.set("status_payload", objectPayload(progress, "progress"));
        }
        return rest.patch(taskPath(taskId), payload);
    }

    public JsonNode success(String taskId, Object result) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode().put("status", "success");
        payload.set("result_payload", objectPayload(MAPPER.valueToTree(result), "result"));
        return rest.patch(taskPath(taskId), payload);
    }

    public JsonNode failure(String taskId, String error) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode()
                .put("status", "failed")
                .put("error_message", error == null || error.isBlank() ? "Task execution failed" : error);
        return rest.patch(taskPath(taskId), payload);
    }

    private static String taskPath(String taskId) {
        return BASE + "/" + RestClient.pathSegment(taskId);
    }

    private static ObjectNode objectPayload(JsonNode value, String field) {
        if (value != null && value.isObject()) {
            return (ObjectNode) value;
        }
        return MAPPER.createObjectNode().set(field, value == null ? MAPPER.nullNode() : value);
    }
}
