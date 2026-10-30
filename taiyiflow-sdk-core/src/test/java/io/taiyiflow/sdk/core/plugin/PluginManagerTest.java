package io.taiyiflow.sdk.core.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginManagerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void registerAndExecute() {
        PluginManager pm = new PluginManager();
        pm.register("greet", call -> ToolResult.success(call.getId(), mapper.createObjectNode().put("msg", "hello")));

        ToolCall call = new ToolCall("tc-1", "greet", null);
        ToolResult result = pm.execute(call);

        assertTrue(result.isSuccess());
        assertEquals("hello", result.getResult().get("msg").asText());
    }

    @Test
    void unknownToolReturnsError() {
        PluginManager pm = new PluginManager();
        ToolCall call = new ToolCall("tc-1", "unknown", null);
        ToolResult result = pm.execute(call);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("No handler"));
    }

    @Test
    void unregisterRemovesHandler() {
        PluginManager pm = new PluginManager();
        pm.register("tool", call -> ToolResult.success(call.getId(), null));
        assertTrue(pm.hasHandler("tool"));

        pm.unregister("tool");
        assertFalse(pm.hasHandler("tool"));
    }

    @Test
    void handlerExceptionReturnsErrorResult() {
        PluginManager pm = new PluginManager();
        pm.register("failing", call -> {
            throw new RuntimeException("boom");
        });

        ToolCall call = new ToolCall("tc-1", "failing", null);
        ToolResult result = pm.execute(call);

        assertFalse(result.isSuccess());
        assertEquals("boom", result.getError());
    }
}
