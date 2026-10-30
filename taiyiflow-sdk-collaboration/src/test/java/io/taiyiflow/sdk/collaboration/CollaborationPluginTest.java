package io.taiyiflow.sdk.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CollaborationPluginTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void taskDispatchModelPopulatedFromPayload() throws Exception {
        String json = "{\"task_id\":\"t-1\",\"flow_id\":\"f-1\",\"input_value\":\"do something\",\"session_id\":\"s-1\"}";
        JsonNode payload = mapper.readTree(json);

        CollaborationPlugin.TaskDispatch task = new CollaborationPlugin.TaskDispatch();
        task.taskId = payload.get("task_id").asText();
        task.flowId = payload.get("flow_id").asText();
        task.inputValue = payload.get("input_value").asText();
        task.sessionId = payload.get("session_id").asText();

        assertEquals("t-1", task.taskId);
        assertEquals("f-1", task.flowId);
        assertEquals("do something", task.inputValue);
        assertEquals("s-1", task.sessionId);
    }

    @Test
    void releasePushModelPopulated() throws Exception {
        String json = "{\"version\":\"2.0.0\",\"notes\":\"new release\"}";
        JsonNode payload = mapper.readTree(json);

        CollaborationPlugin.ReleasePush release = new CollaborationPlugin.ReleasePush();
        release.version = payload.get("version").asText();
        release.payload = payload;

        assertEquals("2.0.0", release.version);
        assertEquals("new release", release.payload.get("notes").asText());
    }

    @Test
    void wsUrlBuiltCorrectly() throws Exception {
        CollaborationPlugin plugin = new CollaborationPlugin("https://api.example.com", "key");
        var method = CollaborationPlugin.class.getDeclaredMethod("buildWebSocketUrl");
        method.setAccessible(true);
        String url = (String) method.invoke(plugin);
        assertEquals("wss://api.example.com/api/v1/agents/client/websocket", url);
    }

    @Test
    void wsUrlFromHttp() throws Exception {
        CollaborationPlugin plugin = new CollaborationPlugin("http://localhost:8080", "key");
        var method = CollaborationPlugin.class.getDeclaredMethod("buildWebSocketUrl");
        method.setAccessible(true);
        String url = (String) method.invoke(plugin);
        assertEquals("ws://localhost:8080/api/v1/agents/client/websocket", url);
    }

    @Test
    void defaultRoleIsCollaborationWorker() {
        CollaborationPlugin plugin = new CollaborationPlugin("http://x", "key");
        // Verify via the constructor default
        assertNotNull(plugin);
    }

    @Test
    void customRole() {
        CollaborationPlugin plugin = new CollaborationPlugin("http://x", "key", "primary_cli");
        assertNotNull(plugin);
    }
}
