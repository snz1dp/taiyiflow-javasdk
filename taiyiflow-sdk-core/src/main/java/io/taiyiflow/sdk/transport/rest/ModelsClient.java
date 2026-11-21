package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Available models for a flow after server-side config merging. */
public class ModelsClient {
    private final RestClient rest;

    public ModelsClient(RestClient rest) {
        this.rest = rest;
    }

    public JsonNode list(String flowId) throws Exception {
        return list(flowId, null);
    }

    public JsonNode list(String flowId, String clientType) throws Exception {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("client_type", clientType);
        return rest.get(RestClient.appendQuery(
                "/api/v1/agents/flows/" + RestClient.pathSegment(flowId) + "/models", query));
    }
}