package io.taiyiflow.sdk.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Collaboration plugin for passive task reception.
 * Connects to WS /api/v1/agents/client/websocket and receives task dispatches.
 * Default disabled - enable explicitly via TaiyiAgent.builder().enableCollaboration().
 */
public class CollaborationPlugin {

    public static final String ROLE_COLLABORATION_WORKER = "collaboration_worker";
    public static final String ROLE_PRIMARY_CLI = "primary_cli";

    private static final Logger log = LoggerFactory.getLogger(CollaborationPlugin.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;
    private static final long RECONNECT_DELAY_MS = 5_000;
    private static final String WORKER_CAPABILITIES =
            "notification.v1,task.remote_task.v1,task.dialogue_subtask.v1";

    private final String baseUrl;
    private final Supplier<String> accessToken;
    private final String role;
    private final String clientId;
    private final String deviceId;
    private final String instanceId;
    private final Map<String, String> credentialHeaders;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final Map<String, Long> dispatchedTaskVersions = new ConcurrentHashMap<>();

    private volatile WebSocket webSocket;
    private volatile Consumer<TaskDispatch> taskHandler;
    private volatile Predicate<TaskDispatch> taskDispatchHandler;
    private volatile Consumer<JsonNode> taskSnapshotHandler;
    private volatile Consumer<ReleasePush> releaseHandler;
    private volatile Consumer<JsonNode> connectionReadyHandler;
    private volatile Consumer<String> kickedHandler;
    private volatile String sessionId = "";

    public CollaborationPlugin(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, ROLE_COLLABORATION_WORKER);
    }

    public CollaborationPlugin(String baseUrl, String apiKey, String role) {
        this(baseUrl, apiKey, role, UUID.randomUUID().toString(), null,
                ROLE_PRIMARY_CLI.equals(role) ? UUID.randomUUID().toString() : null, Map.of());
    }

    public CollaborationPlugin(String baseUrl, String apiKey, String role,
                               String clientId, String deviceId, String instanceId) {
        this(baseUrl, apiKey, role, clientId, deviceId, instanceId, Map.of());
    }

    public CollaborationPlugin(String baseUrl, String apiKey, String role,
                               String clientId, String deviceId, String instanceId,
                               Map<String, String> credentialHeaders) {
        this(baseUrl, () -> apiKey, role, clientId, deviceId, instanceId, credentialHeaders);
    }

    public CollaborationPlugin(String baseUrl, Supplier<String> accessToken, String role,
                               String clientId, String deviceId, String instanceId,
                               Map<String, String> credentialHeaders) {
        if (!ROLE_COLLABORATION_WORKER.equals(role) && !ROLE_PRIMARY_CLI.equals(role)) {
            throw new IllegalArgumentException("Unsupported collaboration role: " + role);
        }
        if (isBlank(clientId) && isBlank(deviceId)) {
            throw new IllegalArgumentException("clientId or deviceId is required");
        }
        if (ROLE_PRIMARY_CLI.equals(role) && isBlank(instanceId)) {
            throw new IllegalArgumentException("instanceId is required for primary_cli");
        }
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.accessToken = accessToken;
        this.role = role;
        this.clientId = clientId;
        this.deviceId = deviceId;
        this.instanceId = instanceId;
        this.credentialHeaders = Map.copyOf(credentialHeaders);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Set handler for incoming task dispatches.
     */
    public CollaborationPlugin onTask(Consumer<TaskDispatch> handler) {
        this.taskHandler = handler;
        this.taskDispatchHandler = null;
        return this;
    }

    public CollaborationPlugin onTaskDispatch(Predicate<TaskDispatch> handler) {
        this.taskDispatchHandler = handler;
        this.taskHandler = null;
        return this;
    }

    public CollaborationPlugin onTaskSnapshot(Consumer<JsonNode> handler) {
        this.taskSnapshotHandler = handler;
        return this;
    }

    /**
     * Set handler for release push notifications.
     */
    public CollaborationPlugin onRelease(Consumer<ReleasePush> handler) {
        this.releaseHandler = handler;
        return this;
    }

    public CollaborationPlugin onConnectionReady(Consumer<JsonNode> handler) {
        this.connectionReadyHandler = handler;
        return this;
    }

    public CollaborationPlugin onKicked(Consumer<String> handler) {
        this.kickedHandler = handler;
        return this;
    }

    public void start() {
        if (!stopped.get() && running.compareAndSet(false, true)) {
            log.info("Starting collaboration plugin with role: {}", role);
            connect();
            scheduler.scheduleAtFixedRate(this::sendHeartbeat,
                    HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        stopped.set(true);
        if (running.compareAndSet(true, false)) {
            log.info("Stopping collaboration plugin");
            connected.set(false);
            WebSocket current = webSocket;
            webSocket = null;
            if (current != null) {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            }
            scheduler.shutdown();
            taskExecutor.shutdown();
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    public void sendSessionUpdate(String sessionId) {
        this.sessionId = sessionId == null ? "" : sessionId;
        if (connected.get()) {
            sendEnvelope("session.update", mapper.createObjectNode().put("session_id", this.sessionId));
        }
    }

    private void connect() {
        if (!running.get()) {
            return;
        }
        log.debug("Connecting collaboration WS for client {}", clientId);
        WebSocket.Builder builder = httpClient.newWebSocketBuilder();
        credentialHeaders.forEach(builder::header);
        builder.buildAsync(URI.create(buildWebSocketUrl()), new CollaborationListener())
                .thenAccept(this::onConnected)
                .exceptionally(ex -> {
                    log.error("Collaboration WS connection failed", ex);
                    scheduleReconnect();
                    return null;
                });
    }

    void onConnected(WebSocket ws) {
        if (!running.get()) {
            ws.abort();
            return;
        }
        this.webSocket = ws;
        if (!running.get()) {
            if (this.webSocket == ws) {
                this.webSocket = null;
            }
            ws.abort();
            return;
        }
        log.info("Collaboration WS opened; waiting for connection.ready");
    }

    private void scheduleReconnect() {
        connected.set(false);
        if (running.get()) {
            scheduler.schedule(this::connect, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void sendHeartbeat() {
        if (connected.get()) {
            sendEnvelope("connection.ping", mapper.createObjectNode());
        }
    }

    private void fetchRecentTasks() {
        fetchRecentTasks(null);
    }

    private void fetchRecentTasks(JsonNode cursor) {
        ObjectNode payload = mapper.createObjectNode().put("limit", 20);
        if (cursor != null && cursor.isObject()) {
            payload.set("cursor", cursor.deepCopy());
        }
        sendEnvelope("tasks.fetch_recent", payload);
    }

    public void sendTaskAck(String taskId) {
        sendTaskAck(taskId, null);
    }

    public void sendTaskAck(String taskId, Long expectedVersion) {
        ObjectNode payload = mapper.createObjectNode().put("task_id", taskId);
        putVersion(payload, expectedVersion);
        sendEnvelope("task.ack", payload);
    }

    public void sendTaskResult(String taskId, Object result) {
        sendTaskResult(taskId, result, null);
    }

    public void sendTaskResult(String taskId, Object result, Long expectedVersion) {
        ObjectNode payload = mapper.createObjectNode().put("task_id", taskId);
        payload.set("result", mapper.valueToTree(result));
        putVersion(payload, expectedVersion);
        sendEnvelope("task.result", payload);
    }

    public void sendTaskError(String taskId, String error) {
        sendTaskError(taskId, error, null);
    }

    public void sendTaskError(String taskId, String error, Long expectedVersion) {
        ObjectNode payload = mapper.createObjectNode()
                .put("task_id", taskId)
                .put("error", error);
        putVersion(payload, expectedVersion);
        sendEnvelope("task.error", payload);
    }

    public void sendTaskStatus(String taskId, String status) {
        sendTaskStatus(taskId, status, null);
    }

    public void sendTaskStatus(String taskId, String status, Long expectedVersion) {
        ObjectNode payload = mapper.createObjectNode()
                .put("task_id", taskId)
                .put("status", status);
        putVersion(payload, expectedVersion);
        sendEnvelope("task.status", payload);
    }

    public void sendScheduleAck(String scheduleId, String runRecordId) {
        sendScheduleEnvelope("task.ack", scheduleId, runRecordId, null, null, null);
    }

    public void sendScheduleStatus(String scheduleId, String runRecordId, String status) {
        sendScheduleEnvelope("task.status", scheduleId, runRecordId, status, null, null);
    }

    public void sendScheduleResult(String scheduleId, String runRecordId, Object result) {
        sendScheduleEnvelope("task.result", scheduleId, runRecordId, null, result, null);
    }

    public void sendScheduleError(String scheduleId, String runRecordId, String error) {
        sendScheduleEnvelope("task.error", scheduleId, runRecordId, null, null, error);
    }

    private void sendScheduleEnvelope(String event, String scheduleId, String runRecordId,
                                      String status, Object result, String error) {
        ObjectNode payload = mapper.createObjectNode()
                .put("schedule_id", scheduleId)
                .put("run_record_id", runRecordId)
                .put("task_type", "schedule");
        if (status != null) {
            payload.put("status", status);
        }
        if (result != null) {
            payload.set("result", mapper.valueToTree(result));
        }
        if (error != null) {
            payload.put("error", error);
        }
        sendEnvelope(event, payload);
    }

    private void sendEnvelope(String event, JsonNode payload) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("type", ROLE_PRIMARY_CLI.equals(role) ? "primary_cli" : "collaboration");
        envelope.put("event", event);
        envelope.set("payload", payload);
        send(envelope);
    }

    void send(JsonNode envelope) {
        WebSocket current = webSocket;
        if (current == null) {
            return;
        }
        try {
            current.sendText(mapper.writeValueAsString(envelope), true);
        } catch (Exception e) {
            log.error("Failed to send collaboration message", e);
        }
    }

    String buildWebSocketUrl() {
        String base = baseUrl;
        if (base.startsWith("https://")) {
            base = "wss://" + base.substring(8);
        } else if (base.startsWith("http://")) {
            base = "ws://" + base.substring(7);
        } else if (!base.startsWith("ws://") && !base.startsWith("wss://")) {
            base = "wss://" + base;
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("__TOKEN__", accessToken.get());
        if (!isBlank(clientId)) {
            query.put("client-id", clientId);
        }
        if (!isBlank(deviceId)) {
            query.put("device-id", deviceId);
        }
        query.put("connection-role", role);
        query.put("device-type", "java");
        query.put("client-type", "text");
        if (!isBlank(instanceId)) {
            query.put("instance-id", instanceId);
        }
        if (ROLE_COLLABORATION_WORKER.equals(role)) {
            query.put("capabilities", WORKER_CAPABILITIES);
        }
        StringBuilder url = new StringBuilder(base)
                .append("/api/v1/agents/client/websocket?");
        query.forEach((key, value) -> {
            if (url.charAt(url.length() - 1) != '?') {
                url.append('&');
            }
            url.append(encode(key)).append('=').append(encode(value));
        });
        return url.toString();
    }

    private class CollaborationListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            if (!running.get()) {
                webSocket.abort();
                return;
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (!running.get()) {
                return null;
            }
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                handleMessage(message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("Collaboration WS closed: {} {}", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("Collaboration WS error", error);
            scheduleReconnect();
        }
    }

    void handleMessage(String json) {
        try {
            JsonNode envelope = mapper.readTree(json);
            String event = envelope.path("event").asText("");
            JsonNode payload = envelope.path("payload");
            switch (event) {
                case "connection.ready" -> handleConnectionReady(payload);
                case "task.dispatch" -> handleTaskDispatch(payload);
                case "primary_task.dispatch" -> handlePrimaryTaskDispatch(payload);
                case "schedule_task.dispatch" -> handleScheduleTaskDispatch(payload);
                case "tasks.recent" -> handleRecentTasks(payload);
                case "connection.kicked" -> {
                    String reason = payload.path("reason").asText("Kicked by platform");
                    log.warn("Kicked from collaboration connection: {}", reason);
                    stop();
                    if (kickedHandler != null) {
                        kickedHandler.accept(reason);
                    }
                }
                case "release.push" -> handleReleasePush(payload);
                case "connection.ping" -> sendEnvelope("connection.pong", mapper.createObjectNode());
                case "connection.pong" -> log.trace("Received pong");
                default -> log.debug("Unhandled collaboration event: {}", event);
            }
        } catch (Exception e) {
            log.error("Failed to handle collaboration message", e);
        }
    }

    private void handleConnectionReady(JsonNode payload) {
        if (stopped.get()) {
            return;
        }
        connected.set(true);
        log.info("Collaboration connection ready for client {}", payload.path("client_id").asText(clientId));
        if (!sessionId.isBlank()) {
            sendSessionUpdate(sessionId);
        }
        if (ROLE_COLLABORATION_WORKER.equals(role)) {
            fetchRecentTasks();
        }
        if (connectionReadyHandler != null) {
            connectionReadyHandler.accept(payload);
        }
    }

    private void handleTaskDispatch(JsonNode payload) {
        if (taskHandler == null && taskDispatchHandler == null) {
            log.warn("No task handler registered, ignoring task dispatch");
            return;
        }
        if (payload.path("task_id").asText("").isBlank()
                && !payload.path("schedule_id").asText("").isBlank()
                && !payload.path("run_record_id").asText("").isBlank()) {
            handleScheduleTaskDispatch(payload);
            return;
        }
        String taskId = payload.path("task_id").asText("");
        if (taskId.isBlank()) {
            return;
        }
        long version = payload.path("version").asLong(0);
        Long previous = dispatchedTaskVersions.putIfAbsent(taskId, version);
        if (previous != null && (version == 0 || previous >= version)) {
            log.debug("Ignoring duplicate task dispatch: {}", taskId);
            return;
        }
        if (previous != null) {
            dispatchedTaskVersions.put(taskId, version);
        }

        TaskDispatch task = taskFromPayload(payload, "task");
        Long expectedVersion = "user".equals(payload.path("source_principal_type").asText("user"))
                ? null : version;
        if (submitTask(task)) {
            sendTaskAck(taskId, expectedVersion);
        } else if (previous == null) {
            dispatchedTaskVersions.remove(taskId, version);
        } else {
            dispatchedTaskVersions.put(taskId, previous);
        }
    }

    private void handlePrimaryTaskDispatch(JsonNode payload) {
        TaskDispatch task = taskFromPayload(payload, "primary");
        task.instanceId = text(payload, "instance_id");
        submitTask(task);
    }

    private void handleScheduleTaskDispatch(JsonNode payload) {
        if (taskHandler == null && taskDispatchHandler == null) {
            log.warn("No task handler registered, ignoring schedule dispatch");
            return;
        }
        String scheduleId = text(payload, "schedule_id");
        String runRecordId = text(payload, "run_record_id");
        if (scheduleId.isBlank() || runRecordId.isBlank()) {
            log.warn("Ignoring schedule dispatch without schedule_id or run_record_id");
            return;
        }
        String dedupeKey = "schedule:" + runRecordId;
        if (dispatchedTaskVersions.putIfAbsent(dedupeKey, 0L) != null) {
            return;
        }
        TaskDispatch task = taskFromPayload(payload, "schedule");
        if (submitTask(task)) {
            sendScheduleAck(scheduleId, runRecordId);
        } else {
            dispatchedTaskVersions.remove(dedupeKey, 0L);
        }
    }

    private void handleRecentTasks(JsonNode payload) {
        JsonNode tasks = payload.path("tasks");
        if (!tasks.isArray()) {
            return;
        }
        for (JsonNode task : tasks) {
            if (taskSnapshotHandler != null) {
                taskSnapshotHandler.accept(task);
            }
            String status = task.path("status").asText("");
            if ("pending".equals(status) && !task.hasNonNull("cancel_requested_at")) {
                handleTaskDispatch(task);
            }
        }
        JsonNode nextCursor = payload.path("next_cursor");
        if (nextCursor.isObject()) {
            fetchRecentTasks(nextCursor);
        }
    }

    private TaskDispatch taskFromPayload(JsonNode payload, String taskType) {
        JsonNode command = payload.path("command");
        TaskDispatch task = new TaskDispatch();
        task.taskId = text(payload, "task_id");
        task.taskKind = text(payload, "task_kind");
        task.taskType = taskType;
        task.version = payload.path("version").asLong(0);
        task.flowId = firstText(command, payload, "flow_id");
        task.inputValue = firstText(command, payload, "input_value");
        if (task.inputValue.isBlank()) {
            task.inputValue = text(payload, "prompt");
        }
        task.sessionId = firstText(command, payload, "session_id");
        task.scheduleId = text(payload, "schedule_id");
        task.runRecordId = text(payload, "run_record_id");
        task.requestMode = text(payload, "request_mode");
        task.payload = payload;
        return task;
    }

    private boolean submitTask(TaskDispatch task) {
        Predicate<TaskDispatch> dispatchHandler = taskDispatchHandler;
        if (dispatchHandler != null) {
            try {
                boolean accepted = dispatchHandler.test(task);
                if (!accepted) {
                    log.warn("Worker rejected {} dispatch {}", task.taskType, task.taskId);
                }
                return accepted;
            } catch (RuntimeException e) {
                log.warn("Failed to submit {} dispatch {}", task.taskType, task.taskId, e);
                return false;
            }
        }
        Consumer<TaskDispatch> handler = taskHandler;
        if (handler == null) {
            log.warn("No task handler registered, ignoring {} dispatch", task.taskType);
            return false;
        }
        try {
            taskExecutor.submit(() -> handler.accept(task));
            return true;
        } catch (RuntimeException e) {
            log.warn("Failed to submit {} dispatch {}", task.taskType, task.taskId, e);
            return false;
        }
    }

    private void handleReleasePush(JsonNode payload) {
        Consumer<ReleasePush> handler = releaseHandler;
        if (handler == null) {
            return;
        }
        ReleasePush release = new ReleasePush();
        release.version = text(payload, "version");
        release.payload = payload;
        handler.accept(release);
    }

    private static void putVersion(ObjectNode payload, Long expectedVersion) {
        if (expectedVersion != null) {
            payload.put("expected_version", expectedVersion);
        }
    }

    private static String firstText(JsonNode primary, JsonNode fallback, String field) {
        String value = text(primary, field);
        return value.isBlank() ? text(fallback, field) : value;
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.isObject() ? node.path(field).asText("") : "";
    }

    private static String normalizeBaseUrl(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Represents an incoming task dispatch.
     */
    public static class TaskDispatch {
        public String taskId;
        public String taskKind;
        public String taskType;
        public long version;
        public String flowId;
        public String inputValue;
        public String sessionId;
        public String scheduleId;
        public String runRecordId;
        public String requestMode;
        public String instanceId;
        public JsonNode payload;
    }

    /**
     * Represents a release push notification.
     */
    public static class ReleasePush {
        public String version;
        public JsonNode payload;
    }
}
