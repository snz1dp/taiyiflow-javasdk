package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
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
    void serializesPhaseGFields() throws Exception {
        ChatRequest request = new ChatRequest("Hi", "s-3");
        request.setRunId("run-1");
        request.setMessageId("message-1");
        request.setInputFiles(List.of("/tmp/a.txt"));
        request.setRequestType("agent");
        request.setAvailableTools(List.of(mapper.readTree("{\"name\":\"shell\"}")));
        request.setClientMcpServers(mapper.readTree("{\"local\":{\"tools\":[]}}"));

        var json = mapper.readTree(mapper.writeValueAsString(request));
        assertEquals("run-1", json.get("run_id").asText());
        assertEquals("message-1", json.get("message_id").asText());
        assertEquals("/tmp/a.txt", json.get("files").get(0).asText());
        assertEquals("agent", json.get("request_type").asText());
        assertEquals("shell", json.get("available_tools").get(0).get("name").asText());
        assertTrue(json.get("client_mcp_servers").has("local"));
    }

    @Test
    void serializesExecutionControlFields() throws Exception {
        ChatRequest request = new ChatRequest(null, "s-3");
        request.setExecutionMode("resume");
        request.setRunId("run-1");
        request.setExecutionAttemptId("attempt-1");
        request.setAfterEventId("event-1");
        request.setSubscriptionId("subscription-1");
        request.setCheckpointId("checkpoint-1");
        request.setCheckpointVersion(3);
        request.setToolResults(List.of(mapper.readTree("{\"call_id\":\"call-1\",\"result\":\"ok\"}")));
        request.setSubmissionId("submission-1");
        request.setClientId("client-1");
        request.setDeviceId("device-1");
        request.setClientType("device");

        var json = mapper.readTree(mapper.writeValueAsString(request));
        assertEquals("resume", json.required("execution_mode").asText());
        assertEquals("attempt-1", json.required("execution_attempt_id").asText());
        assertEquals("event-1", json.required("after_event_id").asText());
        assertEquals("subscription-1", json.required("subscription_id").asText());
        assertEquals("checkpoint-1", json.required("checkpoint_id").asText());
        assertEquals(3, json.required("checkpoint_version").asInt());
        assertEquals("call-1", json.required("tool_results").get(0).required("call_id").asText());
        assertEquals("submission-1", json.required("submission_id").asText());
        assertEquals("client-1", json.required("client_id").asText());
        assertEquals("device-1", json.required("device_id").asText());
        assertEquals("device", json.required("client_type").asText());
    }

    @Test
    void omitsNullOptionalFields() throws Exception {
        String json = mapper.writeValueAsString(new ChatRequest("Hi", "s-3"));
        assertFalse(json.contains("run_id"));
        assertFalse(json.contains("files"));
        assertFalse(json.contains("client_mcp_servers"));
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
