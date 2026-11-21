package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only agent app catalog ({@code /agents/agent-apps}); installation lifecycle stays on the platform/desktop. */
public class AgentAppClient {
    private final RestClient rest;

    public AgentAppClient(RestClient rest) {
        this.rest = rest;
    }

    public JsonNode list() throws Exception {
        return list(null, 0, 100);
    }

    /** Response is a list of AgentAppRead; limit is capped at 100 by the backend. */
    public JsonNode list(Boolean installedOnly, int offset, int limit) throws Exception {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("installed_only", installedOnly);
        query.put("offset", offset);
        query.put("limit", limit);
        return rest.get(RestClient.appendQuery("/api/v1/agents/agent-apps", query));
    }
}