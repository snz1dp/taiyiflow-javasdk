package io.taiyiflow.sdk.plugins.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoPluginTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void isolatesTransientItemsByTrustedSessionAndClearsThemOnClose() {
        PluginManager plugins = new PluginManager();
        TodoPlugin plugin = TodoPlugin.register(plugins);
        ToolResult first = write(plugins, "flow-a", "session-a", "first", "forged-session");
        ToolResult second = write(plugins, "flow-a", "session-b", "second", "session-a");

        assertTrue(first.isSuccess(), first::getError);
        assertEquals("first", first.getResult().path("items").get(0).path("title").asText());
        assertEquals("second", second.getResult().path("items").get(0).path("title").asText());
        assertTrue(first.getResult().path("transient").asBoolean());
        assertEquals(2, plugin.sessionCount());

        plugins.notifySessionClose("session-a");
        assertEquals(1, plugin.sessionCount());
        plugin.close();
        assertEquals(0, plugin.sessionCount());
    }

    @Test
    void rejectsInvalidListsBeforeChangingSessionState() {
        PluginManager plugins = new PluginManager();
        TodoPlugin plugin = TodoPlugin.register(plugins);
        ArrayNode items = MAPPER.createArrayNode();
        for (int index = 0; index < 101; index++) items.addObject().put("title", "item-" + index);
        ToolResult result = plugins.execute(ToolExecutionContext.builder("flow", "session").build(),
                new ToolCall("1", "todo_write", MAPPER.createObjectNode().set("items", items)));
        assertFalse(result.isSuccess());
        assertEquals(0, plugin.sessionCount());
    }

    private static ToolResult write(PluginManager plugins, String flow, String session,
                                    String title, String forgedSession) {
        ArrayNode items = MAPPER.createArrayNode();
        items.addObject().put("title", title).put("status", "in_progress").put("notes", "temporary");
        return plugins.execute(ToolExecutionContext.builder(flow, session).build(),
                new ToolCall("1", "todo_write", MAPPER.createObjectNode()
                        .put("session_id", forgedSession).set("items", items)));
    }
}
