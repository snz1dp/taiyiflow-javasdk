package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only agent plugin catalog ({@code /agents/plugins}); install/uninstall/enable are desktop lifecycle operations. */
public class AgentPluginClient {
    private final RestClient rest;

    public AgentPluginClient(RestClient rest) {
        this.rest = rest;
    }

    public JsonNode list() throws Exception {
        return list(null, null, null, 0, 100);
    }

    /** terminalType is one of desktop|web|cli; response is a list of AgentPluginRead. */
    public JsonNode list(Boolean installedOnly, String agentAppId, String terminalType,
                         int offset, int limit) throws Exception {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("installed_only", installedOnly);
        query.put("agent_app_id", agentAppId);
        query.put("terminal_type", terminalType);
        query.put("offset", offset);
        query.put("limit", limit);
        return rest.get(RestClient.appendQuery("/api/v1/agents/plugins", query));
    }
}