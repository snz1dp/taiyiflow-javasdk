package io.taiyiflow.sdk.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollaborationProtocolTest {

    @Test
    void workerWaitsForReadyThenSyncsSessionAndFetchesTwentyTasks() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        plugin.sendSessionUpdate("session-1");
        assertTrue(plugin.sent.isEmpty());

        plugin.handleMessage("""
                {"type":"collaboration","event":"connection.ready","payload":{"client_id":"client-1"}}
                """);

        assertTrue(plugin.isConnected());
        assertEquals(2, plugin.sent.size());
        JsonNode sessionUpdate = plugin.sent.remove();
        assertEquals("collaboration", sessionUpdate.get("type").asText());
        assertEquals("session.update", sessionUpdate.get("event").asText());
        assertEquals("session-1", sessionUpdate.get("payload").get("session_id").asText());
        JsonNode fetch = plugin.sent.remove();
        assertEquals("tasks.fetch_recent", fetch.get("event").asText());
        assertEquals(20, fetch.get("payload").get("limit").asInt());
    }

    @Test
    void primaryCliUsesInstanceHandshakeAndDoesNotFetchWorkerTasks() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_PRIMARY_CLI);
        String url = plugin.buildWebSocketUrl();
        assertTrue(url.contains("connection-role=primary_cli"));
        assertTrue(url.contains("instance-id=instance-1"));
        assertTrue(url.contains("client-id=client-1"));
        assertTrue(url.contains("device-id=device-1"));

        plugin.sendSessionUpdate("primary-session");
        plugin.handleMessage("""
                {"type":"primary_cli","event":"connection.ready","payload":{"client_instance_id":"instance-1"}}
                """);

        assertTrue(plugin.isConnected());
        assertEquals(1, plugin.sent.size());
        JsonNode update = plugin.sent.remove();
        assertEquals("primary_cli", update.get("type").asText());
        assertEquals("session.update", update.get("event").asText());
    }

    @Test
    void primaryCliRequiresInstanceId() {
        assertThrows(IllegalArgumentException.class, () -> new CollaborationPlugin(
                "http://localhost", "key", CollaborationPlugin.ROLE_PRIMARY_CLI,
                "client-1", null, null));
    }

    @Test
    void scheduleDispatchAcknowledgesAndReportsWithScheduleTaskType() throws Exception {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<CollaborationPlugin.TaskDispatch> received = new AtomicReference<>();
        plugin.onTask(task -> {
            received.set(task);
            handled.countDown();
        });

        plugin.handleMessage("""
                {"type":"collaboration","event":"schedule_task.dispatch","payload":{"schedule_id":"schedule-1","run_record_id":"run-record-1","prompt":"daily report","request_mode":"agent","flow_id":"flow-1","session_id":"session-1"}}
                """);

        assertTrue(handled.await(3, TimeUnit.SECONDS));
        CollaborationPlugin.TaskDispatch task = received.get();
        assertEquals("schedule", task.taskType);
        assertEquals("schedule-1", task.scheduleId);
        assertEquals("run-record-1", task.runRecordId);
        assertEquals("daily report", task.inputValue);
        assertEquals("agent", task.requestMode);

        JsonNode ack = plugin.sent.remove();
        assertSchedulePayload(ack, "task.ack");
        plugin.sendScheduleResult("schedule-1", "run-record-1", Map.of("summary", "done"));
        JsonNode result = plugin.sent.remove();
        assertSchedulePayload(result, "task.result");
        assertEquals("done", result.get("payload").get("result").get("summary").asText());
    }

    @Test
    void taskDispatchReadsCommandAndDeduplicatesByVersion() throws Exception {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<CollaborationPlugin.TaskDispatch> received = new AtomicReference<>();
        plugin.onTask(task -> {
            received.set(task);
            handled.countDown();
        });
        String message = """
                {"type":"collaboration","event":"task.dispatch","payload":{"task_id":"task-1","task_kind":"remote_task","version":7,"source_principal_type":"agent","command":{"flow_id":"flow-1","input_value":"run","session_id":"session-1"}}}
                """;

        plugin.handleMessage(message);
        assertTrue(handled.await(3, TimeUnit.SECONDS));
        plugin.handleMessage(message);

        assertEquals(1, plugin.sent.size());
        JsonNode ack = plugin.sent.remove();
        assertEquals("task.ack", ack.get("event").asText());
        assertEquals(7, ack.get("payload").get("expected_version").asLong());
        assertEquals("flow-1", received.get().flowId);
        assertEquals("run", received.get().inputValue);
        assertEquals("session-1", received.get().sessionId);
    }

    @Test
    void recentTasksReplayOnlyPendingWorkThroughTaskHandler() throws Exception {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<CollaborationPlugin.TaskDispatch> received = new AtomicReference<>();
        plugin.onTask(task -> {
            received.set(task);
            handled.countDown();
        });

        plugin.handleMessage("""
                {"type":"collaboration","event":"tasks.recent","payload":{"tasks":[{"task_id":"task-1","status":"pending","command":{"input_value":"recover"}},{"task_id":"task-2","status":"success","command":{"input_value":"skip"}}]}}
                """);

        assertTrue(handled.await(3, TimeUnit.SECONDS));
        assertEquals("task-1", received.get().taskId);
        assertEquals("recover", received.get().inputValue);
        assertEquals(1, plugin.sent.size());
        assertEquals("task.ack", plugin.sent.peek().get("event").asText());
    }

    @Test
    void recentTasksDoNotReplayDispatchedWorkAndFollowCursor() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        AtomicReference<JsonNode> snapshot = new AtomicReference<>();
        plugin.onTaskSnapshot(snapshot::set);
        plugin.onTask(task -> {
            throw new AssertionError("Dispatched work must not be replayed");
        });

        plugin.handleMessage("""
                {"type":"collaboration","event":"tasks.recent","payload":{"tasks":[{"task_id":"task-1","status":"dispatched","command":{"input_value":"unknown"}}],"next_cursor":{"task_id":"task-1","updated_at":"2026-11-11T12:00:00Z"}}}
                """);

        assertEquals("task-1", snapshot.get().get("task_id").asText());
        assertEquals(1, plugin.sent.size());
        JsonNode fetch = plugin.sent.remove();
        assertEquals("tasks.fetch_recent", fetch.get("event").asText());
        assertEquals("task-1", fetch.get("payload").get("cursor").get("task_id").asText());
    }

    @Test
    void primaryTaskDispatchUsesSameTaskHandlerWithoutWorkerAck() throws Exception {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_PRIMARY_CLI);
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<CollaborationPlugin.TaskDispatch> received = new AtomicReference<>();
        plugin.onTask(task -> {
            received.set(task);
            handled.countDown();
        });

        plugin.handleMessage("""
                {"type":"primary_cli","event":"primary_task.dispatch","payload":{"prompt":"inspect","instance_id":"instance-1","session_id":"session-1"}}
                """);

        assertTrue(handled.await(3, TimeUnit.SECONDS));
        assertTrue(plugin.sent.isEmpty());
        assertEquals("primary", received.get().taskType);
        assertEquals("inspect", received.get().inputValue);
        assertEquals("instance-1", received.get().instanceId);
    }

    @Test
    void reconnectUrlReadsLatestAccessToken() {
        AtomicReference<String> token = new AtomicReference<>("access-1");
        CollaborationPlugin plugin = new CollaborationPlugin(
                "http://localhost", token::get, CollaborationPlugin.ROLE_COLLABORATION_WORKER,
                "client-1", "device-1", null, Map.of());
        assertTrue(plugin.buildWebSocketUrl().contains("__TOKEN__=access-1"));

        token.set("access-2");

        assertTrue(plugin.buildWebSocketUrl().contains("__TOKEN__=access-2"));
    }

    @Test
    void readyAndKickedNotifyRuntimeHandlers() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        AtomicReference<String> kickedReason = new AtomicReference<>();
        AtomicReference<JsonNode> ready = new AtomicReference<>();
        plugin.onConnectionReady(ready::set).onKicked(kickedReason::set);

        plugin.handleMessage("""
                {"type":"collaboration","event":"connection.ready","payload":{"client_id":"client-1"}}
                """);
        plugin.handleMessage("""
                {"type":"collaboration","event":"connection.kicked","payload":{"reason":"another instance"}}
                """);

        assertEquals("client-1", ready.get().required("client_id").asText());
        assertEquals("another instance", kickedReason.get());
    }

    @Test
    void kickedConnectionStopsWithoutReconnect() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        plugin.start();
        plugin.handleMessage("""
                {"type":"collaboration","event":"connection.kicked","payload":{}}
                """);
        assertFalse(plugin.isRunning());
        assertFalse(plugin.isConnected());
    }

    private static void assertSchedulePayload(JsonNode envelope, String event) {
        assertEquals(event, envelope.get("event").asText());
        JsonNode payload = envelope.get("payload");
        assertEquals("schedule", payload.get("task_type").asText());
        assertEquals("schedule-1", payload.get("schedule_id").asText());
        assertEquals("run-record-1", payload.get("run_record_id").asText());
    }

    private static final class RecordingPlugin extends CollaborationPlugin {
        private final ConcurrentLinkedQueue<JsonNode> sent = new ConcurrentLinkedQueue<>();

        private RecordingPlugin(String role) {
            super("http://localhost", "key", role, "client-1", "device-1",
                    ROLE_PRIMARY_CLI.equals(role) ? "instance-1" : null, Map.of());
        }

        @Override
        void send(JsonNode envelope) {
            sent.add(envelope.deepCopy());
        }
    }
}
