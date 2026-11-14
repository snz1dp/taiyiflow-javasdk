package io.taiyiflow.sdk.plugins.todo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.transport.envelope.ToolResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.taiyiflow.sdk.plugins.ToolDefinitions.tool;

public final class TodoPlugin implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SESSIONS = 100;
    private static final int MAX_ITEMS = 100;
    private static final int MAX_TOTAL_CHARACTERS = 100_000;
    private static final Set<String> STATUSES = Set.of("not_started", "in_progress", "completed");
    private final Map<String, ArrayNode> sessions = new HashMap<>();

    public static TodoPlugin register(PluginManager plugins) {
        TodoPlugin plugin = new TodoPlugin();
        String schema = "{\"items\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
                + "\"title\":{\"type\":\"string\"},\"status\":{\"type\":\"string\","
                + "\"enum\":[\"not_started\",\"in_progress\",\"completed\"]},\"notes\":{\"type\":\"string\"}},"
                + "\"required\":[\"title\"]}}}";
        plugins.register(tool("todo_write", "Replace the transient in-memory Todo list for this session.",
                schema, "items"), (PluginManager.ContextualToolHandler) (context, call) -> {
            try {
                return ToolResult.success(call.getId(), plugin.write(
                        context.getFlowId(), context.getSessionId(), call.getArguments()));
            } catch (Exception exception) {
                return ToolResult.error(call.getId(), exception.getMessage());
            }
        });
        plugins.registerSessionHook(new SessionHook() {
            @Override
            public void onClose(String sessionId) {
                plugin.closeSession(sessionId);
            }
        });
        plugins.registerCloseable(plugin);
        return plugin;
    }

    private synchronized ObjectNode write(String flowId, String sessionId, JsonNode arguments) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        JsonNode items = arguments == null ? null : arguments.get("items");
        if (items == null || !items.isArray()) throw new IllegalArgumentException("items must be an array");
        if (items.size() > MAX_ITEMS) throw new IllegalArgumentException("Todo item limit exceeded");
        ArrayNode normalized = MAPPER.createArrayNode();
        int characters = 0;
        for (JsonNode item : items) {
            String title = item.path("title").asText("").trim();
            if (title.isEmpty() || title.length() > 500) {
                throw new IllegalArgumentException("Each Todo title must contain 1 to 500 characters");
            }
            String notes = item.path("notes").asText("").trim();
            if (notes.length() > 4000) throw new IllegalArgumentException("Todo notes exceed the length limit");
            String status = item.path("status").asText("not_started");
            if (!STATUSES.contains(status)) throw new IllegalArgumentException("Unsupported Todo status");
            characters += title.length() + notes.length();
            if (characters > MAX_TOTAL_CHARACTERS) throw new IllegalArgumentException("Todo memory limit exceeded");
            ObjectNode entry = normalized.addObject();
            entry.put("title", title);
            entry.put("status", status);
            entry.put("notes", notes);
        }
        String key = (flowId == null ? "" : flowId) + "\0" + sessionId;
        if (!sessions.containsKey(key) && sessions.size() >= MAX_SESSIONS) {
            throw new IllegalStateException("Todo session limit exceeded");
        }
        sessions.put(key, normalized);
        ObjectNode result = MAPPER.createObjectNode();
        result.set("items", normalized.deepCopy());
        result.put("count", normalized.size());
        result.put("transient", true);
        return result;
    }

    private synchronized void closeSession(String sessionId) {
        sessions.keySet().removeIf(key -> key.endsWith("\0" + sessionId));
    }

    synchronized int sessionCount() {
        return sessions.size();
    }

    @Override
    public synchronized void close() {
        sessions.clear();
    }
}
