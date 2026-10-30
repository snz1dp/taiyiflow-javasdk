package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void constructorSetsFields() {
        ChatRequest request = new ChatRequest("Hello", "session-1");
        assertEquals("Hello", request.getInputValue());
        assertEquals("session-1", request.getSessionId());
        assertNull(request.getTweaks());
    }

    @Test
    void serializeUsesSnakeCase() throws Exception {
        ChatRequest request = new ChatRequest("Hi", "s-2");
        String json = mapper.writeValueAsString(request);
        assertTrue(json.contains("\"input_value\":\"Hi\""));
        assertTrue(json.contains("\"session_id\":\"s-2\""));
    }

    @Test
    void tweaksSerialized() throws Exception {
        ChatRequest request = new ChatRequest("Hi", "s-3");
        request.setTweaks(Map.of("node1", mapper.createObjectNode().put("key", "val")));
        String json = mapper.writeValueAsString(request);
        assertTrue(json.contains("\"tweaks\""));
        assertTrue(json.contains("\"node1\""));
    }

    @Test
    void deserializeRoundTrip() throws Exception {
        ChatRequest original = new ChatRequest("Test", "s-4");
        String json = mapper.writeValueAsString(original);
        ChatRequest parsed = mapper.readValue(json, ChatRequest.class);
        assertEquals("Test", parsed.getInputValue());
        assertEquals("s-4", parsed.getSessionId());
    }
}
