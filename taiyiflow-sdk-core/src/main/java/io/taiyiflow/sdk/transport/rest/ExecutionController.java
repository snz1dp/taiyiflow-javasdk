package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.transport.envelope.AgentLoopControlResult;
import io.taiyiflow.sdk.transport.envelope.ResumableCheckpoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ExecutionController {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final RestClient restClient;
    private final String clientId;
    private final String deviceId;

    public ExecutionController(RestClient restClient, String clientId, String deviceId) {
        this.restClient = restClient;
        this.clientId = requireText(clientId, "clientId");
        this.deviceId = deviceId;
    }

    public AgentLoopControlResult pause(String sessionId, String runId) throws Exception {
        ObjectNode body = controlBody(sessionId, runId);
        body.put("client_id", clientId);
        if (deviceId != null && !deviceId.isBlank()) {
            body.put("device_id", deviceId);
        }
        JsonNode response = restClient.post(controlPath(sessionId, "pause"), body);
        return mapper.treeToValue(response, AgentLoopControlResult.class);
    }

    public AgentLoopControlResult cancel(String sessionId, String runId) throws Exception {
        JsonNode response = restClient.post(controlPath(sessionId, "cancel"), controlBody(sessionId, runId));
        return mapper.treeToValue(response, AgentLoopControlResult.class);
    }

    public List<ResumableCheckpoint> listResumable(String flowId, String sessionId) throws Exception {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("client_id", clientId);
        query.put("device_id", deviceId);
        query.put("flow_id", flowId);
        query.put("session_id", sessionId);
        JsonNode response = restClient.get(RestClient.appendQuery("/api/v1/run/checkpoints/resumable", query));
        if (!response.isArray()) {
            throw new IllegalStateException("Resumable checkpoint response must be an array");
        }
        List<ResumableCheckpoint> checkpoints = new ArrayList<>();
        for (JsonNode item : response) {
            checkpoints.add(mapper.treeToValue(item, ResumableCheckpoint.class));
        }
        return List.copyOf(checkpoints);
    }

    private static ObjectNode controlBody(String sessionId, String runId) {
        requireText(sessionId, "sessionId");
        ObjectNode body = mapper.createObjectNode();
        if (runId != null && !runId.isBlank()) {
            body.put("run_id", runId);
        }
        body.put("request_id", UUID.randomUUID().toString());
        return body;
    }

    private static String controlPath(String sessionId, String operation) {
        return "/api/v1/run/sessions/" + RestClient.pathSegment(requireText(sessionId, "sessionId")) + "/" + operation;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
