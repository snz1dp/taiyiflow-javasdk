package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parseMessageEvent() throws Exception {
        String json = "{\"event\":\"message\",\"data\":{\"chunk\":\"Hello\"}}";
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
        String json = "{\"event\":\"error\",\"data\":{\"error\":\"something broke\",\"type\":\"test\",\"persisted\":true}}";
        StreamEvent event = mapper.readValue(json, StreamEvent.class);
        assertTrue(event.isError());
        assertEquals("something broke", event.getErrorMessage());
        assertEquals("test", event.getErrorType());
    }

    @Test
    void errorTypeReadsMachineCodeAndIsNullWhenAbsent() throws Exception {
        StreamEvent license = mapper.readValue(
                "{\"event\":\"error\",\"data\":{\"error\":\"产品服务授权已到期，请联系厂商购买授权\",\"type\":\"license_expired\",\"persisted\":false}}",
                StreamEvent.class);
        assertEquals("license_expired", license.getErrorType());
        assertNull(mapper.readValue("{\"event\":\"error\",\"data\":{\"error\":\"x\"}}", StreamEvent.class).getErrorType());
        assertNull(new StreamEvent("error", null).getErrorType());
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
    void legacyTextAndContentAreNotMessageChunks() throws Exception {
        String json = "{\"event\":\"message\",\"data\":{\"text\":\"old\",\"content\":\"older\"}}";
        StreamEvent event = mapper.readValue(json, StreamEvent.class);
        assertNull(event.getMessageText());
    }

    @Test
    void messageTextNullWhenNoChunk() throws Exception {
        String json = "{\"event\":\"message\",\"data\":{\"other\":\"value\"}}";
        StreamEvent event = mapper.readValue(json, StreamEvent.class);
        assertNull(event.getMessageText());
    }

    @Test
    void identifiesAllProtocolEvents() throws Exception {
        assertTrue(mapper.readValue("{\"event\":\"bytes\",\"data\":{}}", StreamEvent.class).isBytes());
        assertTrue(mapper.readValue("{\"event\":\"tool_call\",\"data\":{}}", StreamEvent.class).isToolCall());
        assertTrue(mapper.readValue("{\"event\":\"file_written\",\"data\":{}}", StreamEvent.class).isFileWritten());
        assertTrue(mapper.readValue("{\"event\":\"client_tool\",\"data\":{}}", StreamEvent.class).isClientTool());
        assertTrue(mapper.readValue("{\"event\":\"mcp_tool\",\"data\":{}}", StreamEvent.class).isMcpTool());
        assertTrue(mapper.readValue("{\"event\":\"built_in\",\"data\":{}}", StreamEvent.class).isBuiltIn());
    }

    @Test
    void nullDataHandledGracefully() {
        StreamEvent event = new StreamEvent("message", null);
        assertNull(event.getMessageText());
        assertTrue(event.isMessage());
    }
}
