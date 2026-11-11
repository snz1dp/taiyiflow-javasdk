package io.taiyiflow.sdk.transport.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class McpToolClient {
    public static final String PROTOCOL_VERSION = "2025-03-26";

    private static final ObjectMapper mapper = new ObjectMapper();

    private final URI endpoint;
    private final Duration timeout;
    private final Map<String, String> headers;
    private final HttpClient httpClient;
    private final AtomicLong requestIds = new AtomicLong();
    private volatile String sessionId;
    private boolean initialized;

    public McpToolClient(String serverUrl, Duration timeout) {
        this(serverUrl, timeout, Map.of());
    }

    public McpToolClient(String serverUrl, Duration timeout, Map<String, String> headers) {
        this.endpoint = URI.create(serverUrl);
        this.timeout = timeout;
        this.headers = Map.copyOf(headers);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    public synchronized List<McpTool> listTools() throws Exception {
        initializeIfNeeded();
        JsonNode result = request("tools/list", mapper.createObjectNode());
        List<McpTool> tools = new ArrayList<>();
        for (JsonNode tool : result.path("tools")) {
            tools.add(new McpTool(tool.required("name").asText(), tool.path("description").asText(null),
                    tool.path("inputSchema").deepCopy()));
        }
        return List.copyOf(tools);
    }

    public synchronized JsonNode callTool(String toolName, JsonNode arguments) throws Exception {
        initializeIfNeeded();
        ObjectNode params = mapper.createObjectNode().put("name", toolName);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return request("tools/call", params);
    }

    public List<String> registerTools(String serverName, PluginManager pluginManager) throws Exception {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("serverName is required");
        }
        List<String> registeredNames = new ArrayList<>();
        for (McpTool tool : listTools()) {
            String registeredName = "c_" + serverName + "_" + tool.name();
            pluginManager.register(registeredName,
                    call -> ToolResult.success(call.getId(), callTool(tool.name(), call.getArguments())));
            registeredNames.add(registeredName);
        }
        return List.copyOf(registeredNames);
    }

    public String getSessionId() {
        return sessionId;
    }

    private void initializeIfNeeded() throws Exception {
        if (initialized) {
            return;
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "taiyiflow-javasdk");
        clientInfo.put("version", "1.0.0");
        request("initialize", params);
        notifyInitialized();
        initialized = true;
    }

    private JsonNode request(String method, JsonNode params) throws Exception {
        long id = requestIds.incrementAndGet();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        payload.set("params", params);
        HttpResponse<String> response = send(payload);
        if (response.statusCode() >= 400) {
            throw new McpException("MCP HTTP error " + response.statusCode());
        }
        response.headers().firstValue("Mcp-Session-Id").filter(value -> !value.isBlank())
                .ifPresent(value -> sessionId = value);
        JsonNode rpc = parseResponse(response, id);
        if (rpc.hasNonNull("error")) {
            throw new McpException("MCP " + method + " failed: " + rpc.path("error").path("message").asText(
                    rpc.path("error").toString()));
        }
        if (!rpc.has("result")) {
            throw new McpException("MCP " + method + " response has no result");
        }
        return rpc.get("result");
    }

    private void notifyInitialized() throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("method", "notifications/initialized");
        HttpResponse<String> response = send(payload);
        if (response.statusCode() >= 400) {
            throw new McpException("MCP initialized notification failed with HTTP " + response.statusCode());
        }
    }

    private HttpResponse<String> send(ObjectNode payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        headers.forEach(builder::header);
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        return httpClient.send(builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode parseResponse(HttpResponse<String> response, long expectedId) throws Exception {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.contains("text/event-stream")) {
            JsonNode matched = null;
            for (String line : response.body().split("\\R")) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                JsonNode candidate = mapper.readTree(line.substring(5).trim());
                if (candidate.path("id").asLong(-1) == expectedId) {
                    matched = candidate;
                }
            }
            if (matched == null) {
                throw new McpException("MCP SSE response has no matching JSON-RPC id");
            }
            return matched;
        }
        JsonNode rpc = mapper.readTree(response.body());
        if (rpc.path("id").asLong(-1) != expectedId) {
            throw new McpException("MCP response id does not match request");
        }
        return rpc;
    }

    public record McpTool(String name, String description, JsonNode inputSchema) {
    }

    public static class McpException extends RuntimeException {
        public McpException(String message) {
            super(message);
        }
    }
}
