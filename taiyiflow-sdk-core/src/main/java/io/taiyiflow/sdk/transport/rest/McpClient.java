package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public class McpClient {
    private static final String BASE = "/api/v1/agents/mcp/servers";
    private final RestClient rest;

    public McpClient(RestClient rest) {
        this.rest = rest;
    }

    public JsonNode list(String flowId, String clientType) throws Exception {
        return rest.get(RestClient.appendQuery(BASE, Map.of("flow_id", flowId, "client_type", clientType)));
    }

    public JsonNode create(JsonNode server) throws Exception {
        return rest.post(BASE, server);
    }

    public JsonNode update(String serverId, JsonNode server) throws Exception {
        return rest.post(serverPath(serverId), server);
    }

    public void delete(String serverId) throws Exception {
        rest.delete(serverPath(serverId));
    }

    public JsonNode setEnabled(String serverId, boolean enabled) throws Exception {
        return rest.postForm(serverPath(serverId) + "/status", Map.of("enabled", String.valueOf(enabled)));
    }

    private static String serverPath(String serverId) {
        return BASE + "/" + RestClient.pathSegment(serverId);
    }
}
