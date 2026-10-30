package io.taiyiflow.sdk.core.plugin;

import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages tool plugins (built-in and custom).
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final Map<String, ToolHandler> handlers = new ConcurrentHashMap<>();

    public void register(String toolName, ToolHandler handler) {
        handlers.put(toolName, handler);
        log.debug("Registered tool handler: {}", toolName);
    }

    public void unregister(String toolName) {
        handlers.remove(toolName);
    }

    public boolean hasHandler(String toolName) {
        return handlers.containsKey(toolName);
    }

    public ToolResult execute(ToolCall toolCall) {
        ToolHandler handler = handlers.get(toolCall.getName());
        if (handler == null) {
            return ToolResult.error(toolCall.getId(), "No handler for tool: " + toolCall.getName());
        }
        try {
            return handler.execute(toolCall);
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolCall.getName(), e);
            return ToolResult.error(toolCall.getId(), e.getMessage());
        }
    }

    /**
     * Interface for tool execution handlers.
     */
    @FunctionalInterface
    public interface ToolHandler {
        ToolResult execute(ToolCall toolCall) throws Exception;
    }
}
