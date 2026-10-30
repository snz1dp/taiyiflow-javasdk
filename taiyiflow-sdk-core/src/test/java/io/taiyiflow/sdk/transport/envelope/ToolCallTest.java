package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseToolCall() throws Exception {
        String json = "{\"id\":\"tc-1\",\"name\":\"get_weather\",\"arguments\":{\"city\":\"Beijing\"}}";
        ToolCall call = mapper.readValue(json, ToolCall.class);
        assertEquals("tc-1", call.getId());
        assertEquals("get_weather", call.getName());
        assertEquals("Beijing", call.getArguments().get("city").asText());
    }

    @Test
    void constructorSetsFields() {
        ToolCall call = new ToolCall("tc-2", "shell", null);
        assertEquals("tc-2", call.getId());
        assertEquals("shell", call.getName());
        assertNull(call.getArguments());
    }

    @Test
    void serializeRoundTrip() throws Exception {
        ToolCall call = new ToolCall("tc-3", "search", mapper.readTree("{\"pattern\":\"test\"}"));
        String json = mapper.writeValueAsString(call);
        ToolCall parsed = mapper.readValue(json, ToolCall.class);
        assertEquals(call.getId(), parsed.getId());
        assertEquals(call.getName(), parsed.getName());
        assertEquals("test", parsed.getArguments().get("pattern").asText());
    }
}
