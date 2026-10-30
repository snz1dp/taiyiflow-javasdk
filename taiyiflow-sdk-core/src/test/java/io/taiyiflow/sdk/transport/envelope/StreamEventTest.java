package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseMessageEvent() throws Exception {
        String json = "{\"event\":\"message\",\"data\":{\"text\":\"Hello\"}}";
        StreamEvent event = mapper.readValue(json, StreamEvent.class);
        assertEquals("message", event.getEvent());
        assertTrue(event.isMessage());
        assertFalse(event.isHeartbeat());
        assertEquals("Hello", event.getMessageText());
    }

    @Test
    void parseHeartbeatEvent() throws Exception {
        String json = "{\"event\":\"heartbeat\",\"data\":{}}";
        StreamEvent event = mapper.readValue(json, StreamEvent.class);
        assertTrue(event.isHeartbeat());
        assertNull(event.getMessageText());
    }

    @Test
    void parseErrorEvent() throws Exception {
        String json = "{\"event\":\"error\",\"data\":{\"message\":\"something broke\"}}";
        StreamEvent event = mapper.readValue(json, StreamEvent.class);
        assertTrue(event.isError());
    }

    @Test
    void parseCloseEvent() throws Exception {
        String json = "{\"event\":\"close\",\"data\":{}}";
        StreamEvent event = mapper.readValue(json, StreamEvent.class);
        assertTrue(event.isClose());
    }

    @Test
    void parseCustomEvent() throws Exception {
        String json = "{\"event\":\"custom\",\"data\":{\"type\":\"tool_call\"}}";
        StreamEvent event = mapper.readValue(json, StreamEvent.class);
        assertTrue(event.isCustom());
        assertEquals("tool_call", event.getData().get("type").asText());
    }

    @Test
    void messageTextFallbackToContent() throws Exception {
        String json = "{\"event\":\"message\",\"data\":{\"content\":\"Fallback text\"}}";
        StreamEvent event = mapper.readValue(json, StreamEvent.class);
        assertEquals("Fallback text", event.getMessageText());
    }

    @Test
    void messageTextNullWhenNoTextOrContent() throws Exception {
        String json = "{\"event\":\"message\",\"data\":{\"other\":\"value\"}}";
        StreamEvent event = mapper.readValue(json, StreamEvent.class);
        assertNull(event.getMessageText());
    }

    @Test
    void nullDataHandledGracefully() {
        StreamEvent event = new StreamEvent("message", null);
        assertNull(event.getMessageText());
        assertTrue(event.isMessage());
    }
}
