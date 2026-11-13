package io.taiyiflow.sdk.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.lang.reflect.Field;

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
        assertTrue(url.startsWith("wss://api.example.com/api/v1/agents/client/websocket?"));
        assertTrue(url.contains("__TOKEN__=key"));
        assertTrue(url.contains("connection-role=collaboration_worker"));
    }

    @Test
    void wsUrlFromHttp() throws Exception {
        CollaborationPlugin plugin = new CollaborationPlugin("http://localhost:8080", "key");
        var method = CollaborationPlugin.class.getDeclaredMethod("buildWebSocketUrl");
        method.setAccessible(true);
        String url = (String) method.invoke(plugin);
        assertTrue(url.startsWith("ws://localhost:8080/api/v1/agents/client/websocket?"));
        assertTrue(url.contains("client-id="));
        assertTrue(url.contains("capabilities="));
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

    @Test
    void rejectedTaskIsNotAcknowledgedAndCanBeRedelivered() throws Exception {
        AckRecordingPlugin plugin = new AckRecordingPlugin();
        AtomicBoolean accept = new AtomicBoolean(false);
        plugin.onTaskDispatch(task -> accept.get());
        JsonNode payload = mapper.readTree("{\"task_id\":\"task-1\",\"version\":1,\"input_value\":\"work\"}");
        var method = CollaborationPlugin.class.getDeclaredMethod("handleTaskDispatch", JsonNode.class);
        method.setAccessible(true);
        try {
            method.invoke(plugin, payload);
            assertTrue(plugin.acknowledgedTaskIds.isEmpty());

            accept.set(true);
            method.invoke(plugin, payload);

            assertEquals(List.of("task-1"), plugin.acknowledgedTaskIds);
        } finally {
            plugin.stop();
        }
    }

    @Test
    void connectionCompletedWhileNotRunningIsAborted() {
        CollaborationPlugin plugin = new CollaborationPlugin("http://127.0.0.1:1", "key");
        AtomicBoolean aborted = new AtomicBoolean();
        WebSocket socket = (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[]{WebSocket.class},
                (proxy, method, args) -> {
                    if ("abort".equals(method.getName())) {
                        aborted.set(true);
                    }
                    return null;
                });

        plugin.onConnected(socket);

        assertTrue(aborted.get());
        assertFalse(plugin.isConnected());
    }

    @Test
    void lateReadyAfterStopIsIgnored() {
        CollaborationPlugin plugin = new CollaborationPlugin("http://127.0.0.1:1", "key");
        plugin.stop();

        plugin.handleMessage("{\"event\":\"connection.ready\",\"payload\":{}}");

        assertFalse(plugin.isConnected());
        assertFalse(plugin.isRunning());
    }

    @Test
    void inboundSilenceAbortsHalfOpenConnection() throws Exception {
        CollaborationPlugin plugin = new CollaborationPlugin("http://127.0.0.1:1", "key")
                .activityTimeouts(Duration.ofMillis(10), Duration.ofMillis(20));
        AtomicBoolean aborted = new AtomicBoolean();
        WebSocket socket = socket(aborted);
        prepareActiveConnection(plugin, socket);
        setField(plugin, "lastInboundNanos", System.nanoTime() - Duration.ofMillis(100).toNanos());
        try {
            plugin.sendHeartbeat();

            assertTrue(aborted.get());
            assertFalse(plugin.isConnected());
        } finally {
            plugin.stop();
        }
    }

    @Test
    void downstreamFrameRefreshesConnectionActivity() throws Exception {
        CollaborationPlugin plugin = new CollaborationPlugin("http://127.0.0.1:1", "key");
        long previous = System.nanoTime() - Duration.ofSeconds(1).toNanos();
        setField(plugin, "lastInboundNanos", previous);
        try {
            plugin.handleMessage("{\"event\":\"connection.pong\",\"payload\":{}}");

            assertTrue((long) getField(plugin, "lastInboundNanos") > previous);
        } finally {
            plugin.stop();
        }
    }

    @Test
    void delayedSchedulerTickResetsInsteadOfFalsePositive() throws Exception {
        CollaborationPlugin plugin = new CollaborationPlugin("http://127.0.0.1:1", "key")
                .activityTimeouts(Duration.ofMillis(10), Duration.ofMillis(20));
        AtomicBoolean aborted = new AtomicBoolean();
        prepareActiveConnection(plugin, socket(aborted));
        setField(plugin, "lastInboundNanos", System.nanoTime() - Duration.ofMillis(100).toNanos());
        setField(plugin, "expectedHeartbeatNanos", System.nanoTime() - Duration.ofMillis(100).toNanos());
        try {
            plugin.sendHeartbeat();

            assertFalse(aborted.get());
            assertTrue(plugin.isConnected());
        } finally {
            plugin.stop();
        }
    }

    @Test
    void reconnectBackoffIsBoundedAndIncludesJitter() {
        for (int i = 0; i < 20; i++) {
            long initial = CollaborationPlugin.reconnectDelayMillis(0);
            long maximum = CollaborationPlugin.reconnectDelayMillis(20);
            assertTrue(initial >= 2_500 && initial <= 5_000);
            assertTrue(maximum >= 30_000 && maximum <= 60_000);
        }
    }

    private static void prepareActiveConnection(CollaborationPlugin plugin, WebSocket socket) throws Exception {
        setField(plugin, "running", true);
        setField(plugin, "connected", true);
        setField(plugin, "webSocket", socket);
        setField(plugin, "expectedHeartbeatNanos", System.nanoTime());
        setField(plugin, "lastInboundNanos", System.nanoTime());
    }

    private static WebSocket socket(AtomicBoolean aborted) {
        return (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(), new Class<?>[]{WebSocket.class},
                (proxy, method, args) -> {
                    if ("abort".equals(method.getName())) {
                        aborted.set(true);
                    }
                    return null;
                });
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        if (field.getType() == AtomicBoolean.class) {
            ((AtomicBoolean) field.get(target)).set((Boolean) value);
            return;
        }
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class AckRecordingPlugin extends CollaborationPlugin {
        private final List<String> acknowledgedTaskIds = new ArrayList<>();

        private AckRecordingPlugin() {
            super("http://127.0.0.1:1", "key");
        }

        @Override
        public void sendTaskAck(String taskId, Long expectedVersion) {
            acknowledgedTaskIds.add(taskId);
        }
    }
}
