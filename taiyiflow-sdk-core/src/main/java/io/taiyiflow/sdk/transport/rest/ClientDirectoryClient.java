package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Initiator-side directory for remote subtask dispatch: online desktop connections
 * ({@code /agents/client/online-desktops}) and coordination principals
 * ({@code /agents/client/principals}).
 */
public class ClientDirectoryClient {
    private final RestClient rest;

    public ClientDirectoryClient(RestClient rest) {
        this.rest = rest;
    }

    /**
     * {@code {online, connections[]}}; each connection carries client_id/device_id/client_type/
     * device_type/platform/client_version/session_id/session_name/connected_at/can_receive_task.
     * Filter out {@code can_receive_task == false} (e.g. dialogue voice connections) before dispatch.
     */
    public JsonNode onlineDesktops() throws Exception {
        return rest.get("/api/v1/agents/client/online-desktops");
    }

    /** {@code {data: AgentPrincipalRead[], total}} for the current credential owner. */
    public JsonNode principals() throws Exception {
        return rest.get("/api/v1/agents/client/principals");
    }
}