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

    public JsonNode create(ObjectNode payload) throws Exception {
        return rest.post(BASE, payload);
    }

    public JsonNode get(String taskId) throws Exception {
        return rest.get(BASE + "/" + RestClient.pathSegment(taskId));
    }

    public JsonNode cancel(String taskId, String requestId, Long expectedVersion) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode().put("request_id", requestId);
        putVersion(payload, expectedVersion);
        return rest.post(taskPath(taskId) + "/cancel", payload);
    }

    public JsonNode list(Map<String, ?> filters) throws Exception {
        return rest.get(RestClient.appendQuery(BASE, filters));
    }

    public JsonNode running(String taskId, JsonNode progress) throws Exception {
        return running(taskId, progress, null);
    }

    public JsonNode running(String taskId, JsonNode progress, Long expectedVersion) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode().put("status", "running");
        if (progress != null && !progress.isNull()) {
            payload.set("status_payload", objectPayload(progress, "progress"));
        }
        putVersion(payload, expectedVersion);
        return rest.patch(taskPath(taskId), payload);
    }

    public JsonNode success(String taskId, Object result) throws Exception {
        return success(taskId, result, null);
    }

    public JsonNode success(String taskId, Object result, Long expectedVersion) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode().put("status", "success");
        payload.set("result_payload", objectPayload(MAPPER.valueToTree(result), "result"));
        putVersion(payload, expectedVersion);
        return rest.patch(taskPath(taskId), payload);
    }

    public JsonNode failure(String taskId, String error) throws Exception {
        return failure(taskId, error, null);
    }

    public JsonNode failure(String taskId, String error, Long expectedVersion) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode()
                .put("status", "failed")
                .put("error_message", error == null || error.isBlank() ? "Task execution failed" : error);
        putVersion(payload, expectedVersion);
        return rest.patch(taskPath(taskId), payload);
    }

    public JsonNode cancelled(String taskId, String cancelRequestId, Long expectedVersion) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode().put("status", "cancelled");
        payload.putObject("status_payload").put("cancel_request_id", cancelRequestId);
        putVersion(payload, expectedVersion);
        return rest.patch(taskPath(taskId), payload);
    }

    private static void putVersion(ObjectNode payload, Long expectedVersion) {
        if (expectedVersion != null) {
            payload.put("expected_version", expectedVersion);
        }
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
