package io.taiyiflow.sdk.transport.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class StdioMcpManager implements AutoCloseable {
    private final StdioMcpPolicy policy;
    private final Duration requestTimeout;
    private final Map<String, StdioMcpClient> instances = new ConcurrentHashMap<>();
    private final Object capacityLock = new Object();
    private volatile boolean closed;

    public StdioMcpManager(StdioMcpPolicy policy, Duration requestTimeout) {
        this.policy = policy;
        this.requestTimeout = requestTimeout;
    }

    public List<McpToolClient.McpTool> listTools(StdioMcpServerConfig config) throws Exception {
        return instance(config).listTools();
    }

    public JsonNode callTool(StdioMcpServerConfig config, String toolName, JsonNode arguments,
                             ToolExecutionContext.Cancellation cancellation) throws Exception {
        return instance(config).callTool(toolName, arguments, cancellation);
    }

    public List<String> registerTools(String serverName, StdioMcpServerConfig config,
                                      PluginManager pluginManager) throws Exception {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("serverName is required");
        }
        StdioMcpClient client = instance(config);
        List<String> registered = new ArrayList<>();
        for (McpToolClient.McpTool tool : client.listTools()) {
            String registeredName = "c_" + serverName + "_" + tool.name();
            pluginManager.register(registeredName,
                    (PluginManager.ContextualToolHandler) (context, call) -> {
                        try {
                            JsonNode result = client.callTool(tool.name(), call.getArguments(),
                                    context.getCancellation());
                            return ToolResult.success(call.getId(), result);
                        } catch (McpToolClient.McpException exception) {
                            return ToolResult.error(call.getId(), exception.getMessage());
                        }
                    });
            registered.add(registeredName);
        }
        pluginManager.registerCloseable(this);
        return List.copyOf(registered);
    }

    StdioMcpClient instance(StdioMcpServerConfig config) {
        if (closed) {
            throw new IllegalStateException("Stdio MCP manager is closed");
        }
        policy.validate(config);
        String key = config.cacheKey();
        StdioMcpClient existing = instances.get(key);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }
        synchronized (capacityLock) {
            existing = instances.get(key);
            if (existing != null && !existing.isClosed()) {
                return existing;
            }
            if (existing == null && instances.size() >= policy.maxServers()) {
                throw new IllegalStateException("Stdio MCP server limit exceeded");
            }
            StdioMcpClient created = new StdioMcpClient(config, policy, requestTimeout);
            created.onTermination(() -> instances.remove(key, created));
            instances.put(key, created);
            return created;
        }
    }

    int instanceCount() {
        return instances.size();
    }

    @Override
    public void close() {
        closed = true;
        instances.values().forEach(StdioMcpClient::close);
        instances.clear();
    }
}
