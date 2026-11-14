package io.taiyiflow.sdk.core.plugin;

import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolDefinition;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages tool plugins (built-in and custom).
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final Map<String, ToolHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, ToolDefinition> definitions = new ConcurrentHashMap<>();
    private final Set<AutoCloseable> closeables = ConcurrentHashMap.newKeySet();
    private final Set<SessionHook> sessionHooks = ConcurrentHashMap.newKeySet();

    public void register(String toolName, ToolHandler handler) {
        handlers.put(toolName, handler);
        definitions.remove(toolName);
        log.debug("Registered tool handler: {}", toolName);
    }

    public void register(ToolDefinition definition, ToolHandler handler) {
        handlers.put(definition.getName(), handler);
        definitions.put(definition.getName(), definition);
        log.debug("Registered tool handler and definition: {}", definition.getName());
    }

    public void registerCloseable(AutoCloseable closeable) {
        closeables.add(closeable);
    }

    public void registerSessionHook(SessionHook hook) {
        sessionHooks.add(hook);
    }

    public void notifySessionClose(String sessionId) {
        for (SessionHook hook : sessionHooks) {
            try {
                hook.onClose(sessionId);
            } catch (Exception exception) {
                log.warn("Plugin session cleanup failed", exception);
            }
        }
    }

    public void unregister(String toolName) {
        handlers.remove(toolName);
        definitions.remove(toolName);
    }

    public boolean hasHandler(String toolName) {
        return handlers.containsKey(toolName);
    }

    public ToolDefinition getDefinition(String toolName) {
        return definitions.get(toolName);
    }

    public java.util.Collection<ToolDefinition> getDefinitions() {
        return java.util.List.copyOf(definitions.values());
    }

    public ToolResult execute(ToolCall toolCall) {
        return execute(ToolExecutionContext.standalone("standalone"), toolCall);
    }

    public ToolResult execute(ToolExecutionContext context, ToolCall toolCall) {
        ToolHandler handler = handlers.get(toolCall.getName());
        if (handler == null) {
            return ToolResult.error(toolCall.getId(), "No handler for tool: " + toolCall.getName());
        }
        try {
            if (handler instanceof ContextualToolHandler contextual) {
                return contextual.execute(context, toolCall);
            }
            return handler.execute(toolCall);
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolCall.getName(), e);
            return ToolResult.error(toolCall.getId(), e.getMessage());
        }
    }

    public void close() {
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception exception) {
                log.warn("Plugin cleanup failed", exception);
            }
        }
        closeables.clear();
    }

    /**
     * Interface for tool execution handlers.
     */
    @FunctionalInterface
    public interface ContextualToolHandler extends ToolHandler {
        ToolResult execute(ToolExecutionContext context, ToolCall toolCall) throws Exception;

        @Override
        default ToolResult execute(ToolCall toolCall) throws Exception {
            return execute(ToolExecutionContext.standalone("standalone"), toolCall);
        }
    }

    @FunctionalInterface
    public interface ToolHandler {
        ToolResult execute(ToolCall toolCall) throws Exception;
    }
}
