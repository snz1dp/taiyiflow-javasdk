package io.taiyiflow.sdk.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    void taskCancelReadsBackendPayloadWithoutDispatchOrAckSideEffects() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        AtomicReference<CollaborationPlugin.TaskCancel> received = new AtomicReference<>();
        plugin.onTask(task -> {
            throw new AssertionError("Cancellation must not dispatch work");
        });
        plugin.onTaskCancel(received::set);

        plugin.handleMessage("""
                {"type":"collaboration","event":"task.cancel","payload":{"task_id":"task-1","task_kind":"remote_task","version":8,"source_principal_type":"agent","correlation_id":"correlation-1","cancel_request_id":"cancel-1","cancel_requested_at":"2026-01-01T00:00:00+00:00"}}
                """);

        assertTrue(plugin.sent.isEmpty());
        CollaborationPlugin.TaskCancel cancel = received.get();
        assertEquals("task-1", cancel.taskId);
        assertEquals("remote_task", cancel.taskKind);
        assertEquals(8, cancel.version);
        assertEquals("agent", cancel.sourcePrincipalType);
        assertEquals("correlation-1", cancel.correlationId);
        assertEquals("cancel-1", cancel.cancelRequestId);
        assertEquals("2026-01-01T00:00:00+00:00", cancel.cancelRequestedAt);
        assertEquals("task-1", cancel.payload.get("task_id").asText());
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

        awaitUntil(() -> ready.get() != null);
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

    @Test
    void workerHandshakeDeclaresNotificationCapability() {
        RecordingPlugin worker = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        assertTrue(worker.buildWebSocketUrl().contains("notification.v1"));
        assertTrue(worker.buildWebSocketUrl().contains("agent_loop.resume.v1"));
        RecordingPlugin primary = new RecordingPlugin(CollaborationPlugin.ROLE_PRIMARY_CLI);
        assertFalse(primary.buildWebSocketUrl().contains("capabilities="));
    }

    @Test
    void resumeRequestDeliversTypedFieldsAndDeduplicatesByNotificationId() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        java.util.List<CollaborationPlugin.ResumeRequest> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        plugin.onResumeRequested(received::add);
        String message = """
                {"type":"collaboration","event":"agent_loop.resume_requested","payload":{"notification_id":"checkpoint-1:4","checkpoint_id":"checkpoint-1","checkpoint_version":4,"session_id":"session-1","run_id":"run-1","flow_id":"flow-1","resume_stage":"tool_results","reason":"tool_results_ready","expires_at":"2099-01-01T00:00:00+00:00"}}
                """;

        plugin.handleMessage(message);
        plugin.handleMessage(message);

        assertEquals(1, received.size());
        CollaborationPlugin.ResumeRequest request = received.get(0);
        assertEquals("checkpoint-1:4", request.notificationId);
        assertEquals("checkpoint-1", request.checkpointId);
        assertEquals(4, request.checkpointVersion);
        assertEquals("session-1", request.sessionId);
        assertEquals("run-1", request.runId);
        assertEquals("flow-1", request.flowId);
        assertEquals("tool_results_ready", request.reason);
        assertTrue(plugin.sent.isEmpty());

        plugin.sendResumeNotificationAck(request.notificationId, request.checkpointId,
                request.checkpointVersion, "accepted");
        JsonNode ack = plugin.sent.remove();
        assertEquals("agent_loop.resume_notification_ack", ack.get("event").asText());
        assertEquals("checkpoint-1:4", ack.get("payload").get("notification_id").asText());
        assertEquals("checkpoint-1", ack.get("payload").get("checkpoint_id").asText());
        assertEquals(4, ack.get("payload").get("checkpoint_version").asInt());
        assertEquals("accepted", ack.get("payload").get("result").asText());
    }

    @Test
    void resumeRequestWithoutHandlerAnswersIgnoredUnsupported() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);

        plugin.handleMessage("""
                {"type":"collaboration","event":"agent_loop.resume_requested","payload":{"notification_id":"n-1","checkpoint_id":"checkpoint-1","checkpoint_version":2}}
                """);

        assertEquals(1, plugin.sent.size());
        JsonNode ack = plugin.sent.remove();
        assertEquals("agent_loop.resume_notification_ack", ack.get("event").asText());
        assertEquals("ignored_unsupported", ack.get("payload").get("result").asText());
    }

    @Test
    void resumeRequestGuardsExpiredStaleAndInvalidPayloads() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        java.util.List<CollaborationPlugin.ResumeRequest> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        plugin.onResumeRequested(received::add);

        plugin.handleMessage("""
                {"type":"collaboration","event":"agent_loop.resume_requested","payload":{"notification_id":"n-expired","checkpoint_id":"checkpoint-1","checkpoint_version":2,"expires_at":"2000-01-01T00:00:00+00:00"}}
                """);
        assertEquals("ignored_expired", plugin.sent.remove().get("payload").get("result").asText());

        plugin.handleMessage("""
                {"type":"collaboration","event":"agent_loop.checkpoint_state","payload":{"checkpoint_id":"checkpoint-2","checkpoint_version":9,"status":"ready"}}
                """);
        assertTrue(plugin.sent.isEmpty());
        plugin.handleMessage("""
                {"type":"collaboration","event":"agent_loop.resume_requested","payload":{"notification_id":"n-stale","checkpoint_id":"checkpoint-2","checkpoint_version":8}}
                """);
        assertEquals("ignored_stale", plugin.sent.remove().get("payload").get("result").asText());

        plugin.handleMessage("""
                {"type":"collaboration","event":"agent_loop.resume_requested","payload":{"notification_id":"n-invalid","checkpoint_id":"checkpoint-3"}}
                """);
        plugin.handleMessage("""
                {"type":"collaboration","event":"agent_loop.resume_requested","payload":{"notification_id":"n-invalid-2","checkpoint_id":"checkpoint-3","checkpoint_version":0}}
                """);
        assertTrue(plugin.sent.isEmpty());
        assertTrue(received.isEmpty());
    }

    @Test
    void checkpointStateTracksVersionAndCliTunnelEventsAreExcluded() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        AtomicReference<JsonNode> state = new AtomicReference<>();
        AtomicReference<JsonNode> origin = new AtomicReference<>();
        plugin.onCheckpointState(state::set).onOriginAvailable(origin::set);
        java.util.List<CollaborationPlugin.ResumeRequest> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        plugin.onResumeRequested(received::add);

        plugin.handleMessage("""
                {"type":"collaboration","event":"agent_loop.checkpoint_state","payload":{"checkpoint_id":"checkpoint-1","checkpoint_version":5}}
                """);
        assertEquals(5, state.get().path("checkpoint_version").asInt());
        plugin.handleMessage("""
                {"type":"collaboration","event":"agent_loop.origin_available","payload":{"checkpoint_id":"checkpoint-1"}}
                """);
        assertEquals("checkpoint-1", origin.get().path("checkpoint_id").asText());

        plugin.handleMessage("""
                {"type":"collaboration","event":"agent_loop.persistence_activate","payload":{}}
                """);
        plugin.handleMessage("""
                {"type":"collaboration","event":"agent_loop.persistence_active","payload":{}}
                """);
        plugin.handleMessage("""
                {"type":"collaboration","event":"agent_loop.event","payload":{}}
                """);
        assertTrue(plugin.sent.isEmpty());

        plugin.handleMessage("""
                {"type":"collaboration","event":"agent_loop.resume_requested","payload":{"notification_id":"n-1","checkpoint_id":"checkpoint-1","checkpoint_version":5}}
                """);
        assertEquals(1, received.size());
        assertTrue(plugin.sent.isEmpty());
    }

    @Test
    void notificationDeliveryIsAcknowledgedAndHandlerSeesEachDeliveryOnce() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        java.util.List<JsonNode> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        plugin.onNotification(received::add);
        String message = """
                {"type":"collaboration","event":"notification.deliver","payload":{"notification_id":"n-1","delivery_id":"d-1","intent":"notice","title":"审批提醒","delivery":{"status":"pending","ack_at":null,"read_at":null}}}
                """;

        plugin.handleMessage(message);
        plugin.handleMessage(message);

        assertEquals(1, received.size());
        assertEquals("n-1", received.get(0).path("notification_id").asText());
        assertEquals("审批提醒", received.get(0).path("title").asText());
        assertEquals(2, plugin.sent.size());
        for (JsonNode ack : plugin.sent) {
            assertEquals("notification.ack", ack.get("event").asText());
            assertEquals("d-1", ack.get("payload").get("delivery_id").asText());
        }

        plugin.handleMessage("""
                {"type":"collaboration","event":"notification.deliver","payload":{"notification_id":"n-2"}}
                """);
        assertEquals(2, plugin.sent.size());
        assertEquals(1, received.size());
    }

    @Test
    void notificationRecentAcksOnlyUnacknowledgedItemsAndDeduplicatesRedelivery() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        java.util.List<JsonNode> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        plugin.onNotification(received::add);

        plugin.handleMessage("""
                {"type":"collaboration","event":"notification.recent","payload":{"notifications":[{"notification_id":"n-1","delivery_id":"d-1","delivery":{"ack_at":null}},{"notification_id":"n-2","delivery_id":"d-2","delivery":{"ack_at":"2026-11-21T10:00:00+00:00"}}],"next_cursor":"d-2"}}
                """);

        assertEquals(2, received.size());
        assertEquals(1, plugin.sent.size());
        JsonNode ack = plugin.sent.remove();
        assertEquals("notification.ack", ack.get("event").asText());
        assertEquals("d-1", ack.get("payload").get("delivery_id").asText());

        plugin.handleMessage("""
                {"type":"collaboration","event":"notification.deliver","payload":{"notification_id":"n-1","delivery_id":"d-1"}}
                """);
        assertEquals(2, received.size());
        assertEquals(1, plugin.sent.size());
        assertEquals("notification.ack", plugin.sent.remove().get("event").asText());
    }

    @Test
    void notificationReadAndFetchRecentUseBackendRequiredFields() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);

        plugin.markNotificationRead("d-1");
        JsonNode read = plugin.sent.remove();
        assertEquals("notification.read", read.get("event").asText());
        assertEquals("d-1", read.get("payload").get("delivery_id").asText());
        assertThrows(IllegalArgumentException.class, () -> plugin.markNotificationRead(" "));

        plugin.fetchRecentNotifications(50, "d-2");
        JsonNode fetch = plugin.sent.remove();
        assertEquals("notification.fetch_recent", fetch.get("event").asText());
        assertEquals(50, fetch.get("payload").get("limit").asInt());
        assertEquals("d-2", fetch.get("payload").get("cursor").asText());

        plugin.fetchRecentNotifications(100, null);
        JsonNode fetchAll = plugin.sent.remove();
        assertEquals(100, fetchAll.get("payload").get("limit").asInt());
        assertFalse(fetchAll.get("payload").has("cursor"));
        assertThrows(IllegalArgumentException.class, () -> plugin.fetchRecentNotifications(0, null));
        assertThrows(IllegalArgumentException.class, () -> plugin.fetchRecentNotifications(101, null));
    }

    @Test
    void resumeRequestHandlerFailureAllowsPlatformRedelivery() {
        RecordingPlugin plugin = new RecordingPlugin(CollaborationPlugin.ROLE_COLLABORATION_WORKER);
        AtomicInteger attempts = new AtomicInteger();
        List<CollaborationPlugin.ResumeRequest> received = new CopyOnWriteArrayList<>();
        plugin.onResumeRequested(request -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("resume bootstrap failed");
            }
            received.add(request);
        });
        String message = """
                {"type":"collaboration","event":"agent_loop.resume_requested","payload":{"notification_id":"n-redeliver","checkpoint_id":"checkpoint-1","checkpoint_version":3,"session_id":"session-1"}}
                """;

        plugin.handleMessage(message);
        assertTrue(plugin.sent.isEmpty());
        plugin.handleMessage(message);

        assertEquals(2, attempts.get());
        assertEquals(1, received.size());
        plugin.handleMessage(message);
        assertEquals(2, attempts.get());
    }

    @Test
    void concurrentSendsAreSerializedOnTheWebSocket() throws Exception {
        CollaborationPlugin plugin = new CollaborationPlugin(
                "http://localhost", "key", CollaborationPlugin.ROLE_COLLABORATION_WORKER,
                "client-1", "device-1", null, Map.of());
        ProbeWebSocket probe = new ProbeWebSocket();
        java.lang.reflect.Field field = CollaborationPlugin.class.getDeclaredField("webSocket");
        field.setAccessible(true);
        field.set(plugin, probe);

        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int index = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 8; n++) {
                        plugin.sendTaskStatus("task-" + index, "running");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(threads * 8, probe.completed.get());
        assertEquals(0, probe.overlaps.get());
        assertEquals(1, probe.maxConcurrent.get());
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertTrue(condition.getAsBoolean());
    }

    private static final class ProbeWebSocket implements WebSocket {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final AtomicInteger overlaps = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            int current = inFlight.incrementAndGet();
            maxConcurrent.accumulateAndGet(current, Math::max);
            if (current > 1) {
                overlaps.incrementAndGet();
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inFlight.decrementAndGet();
                completed.incrementAndGet();
                return this;
            });
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(java.nio.ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(java.nio.ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(java.nio.ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }
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
