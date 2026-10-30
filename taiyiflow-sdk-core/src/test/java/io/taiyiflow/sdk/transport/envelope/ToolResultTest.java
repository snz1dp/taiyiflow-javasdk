package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successResult() {
        ToolResult result = ToolResult.success("tc-1", mapper.createObjectNode().put("value", 42));
        assertTrue(result.isSuccess());
        assertEquals("tc-1", result.getToolCallId());
        assertEquals(42, result.getResult().get("value").asInt());
        assertNull(result.getError());
    }

    @Test
    void errorResult() {
        ToolResult result = ToolResult.error("tc-2", "Something failed");
        assertFalse(result.isSuccess());
        assertEquals("tc-2", result.getToolCallId());
        assertEquals("Something failed", result.getError());
        assertNull(result.getResult());
    }

    @Test
    void serializeSuccess() throws Exception {
        ToolResult result = ToolResult.success("tc-3", mapper.createObjectNode().put("ok", true));
        String json = mapper.writeValueAsString(result);
        ToolResult parsed = mapper.readValue(json, ToolResult.class);
        assertTrue(parsed.isSuccess());
        assertEquals("tc-3", parsed.getToolCallId());
        assertTrue(parsed.getResult().get("ok").asBoolean());
    }
}
