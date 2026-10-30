package io.taiyiflow.sdk.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Collaboration plugin for passive task reception.
 * Connects to WS /api/v1/agents/client/websocket and receives task dispatches.
 * Default disabled - enable explicitly via TaiyiAgent.builder().enableCollaboration().
 */
public class CollaborationPlugin {

    private static final Logger log = LoggerFactory.getLogger(CollaborationPlugin.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;
    private static final long RECONNECT_DELAY_MS = 5_000;

    private final String baseUrl;
    private final String apiKey;
    private final String role;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private WebSocket webSocket;
    private Consumer<TaskDispatch> taskHandler;
    private Consumer<ReleasePush> releaseHandler;

    public CollaborationPlugin(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, "collaboration_worker");
    }

    public CollaborationPlugin(String baseUrl, String apiKey, String role) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.role = role;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Set handler for incoming task dispatches.
     */
    public CollaborationPlugin onTask(Consumer<TaskDispatch> handler) {
        this.taskHandler = handler;
        return this;
    }

    /**
     * Set handler for release push notifications.
     */
    public CollaborationPlugin onRelease(Consumer<ReleasePush> handler) {
        this.releaseHandler = handler;
        return this;
    }

    /**
     * Start the collaboration connection.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting collaboration plugin with role: {}", role);
            connect();
            scheduler.scheduleAtFixedRate(this::sendHeartbeat, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stop the collaboration connection.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping collaboration plugin");
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            }
            scheduler.shutdown();
            taskExecutor.shutdown();
        }
    }

    private void connect() {
        if (!running.get()) return;

        String wsUrl = buildWebSocketUrl();
        log.debug("Connecting collaboration WS: {}", wsUrl);

        httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + apiKey)
                .buildAsync(URI.create(wsUrl), new CollaborationListener())
                .thenAccept(ws -> {
                    this.webSocket = ws;
                    connected.set(true);
                    log.info("Collaboration WS connected");
                    sendRegistration();
                    fetchRecentTasks();
                })
                .exceptionally(ex -> {
                    log.error("Collaboration WS connection failed", ex);
                    scheduleReconnect();
                    return null;
                });
    }

    private void scheduleReconnect() {
        if (running.get()) {
            connected.set(false);
            scheduler.schedule(this::connect, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void sendRegistration() {
        send(Map.of(
                "type", "register",
                "event", "connection.register",
                "payload", Map.of("role", role)
        ));
    }

    private void sendHeartbeat() {
        if (connected.get() && webSocket != null) {
            send(Map.of("type", "ping"));
        }
    }

    private void fetchRecentTasks() {
        send(Map.of(
                "type", "request",
                "event", "tasks.fetch_recent",
                "payload", Map.of()
        ));
    }

    public void sendTaskAck(String taskId) {
        send(Map.of(
                "type", "response",
                "event", "task.ack",
                "payload", Map.of("task_id", taskId)
        ));
    }

    public void sendTaskResult(String taskId, Object result) {
        send(Map.of(
                "type", "response",
                "event", "task.result",
                "payload", Map.of("task_id", taskId, "result", result)
        ));
    }

    public void sendTaskError(String taskId, String error) {
        send(Map.of(
                "type", "response",
                "event", "task.error",
                "payload", Map.of("task_id", taskId, "error", error)
        ));
    }

    public void sendTaskStatus(String taskId, String status) {
        send(Map.of(
                "type", "response",
                "event", "task.status",
                "payload", Map.of("task_id", taskId, "status", status)
        ));
    }

    private void send(Map<String, Object> envelope) {
        if (webSocket == null) return;
        try {
            String json = mapper.writeValueAsString(envelope);
            webSocket.sendText(json, true);
        } catch (Exception e) {
            log.error("Failed to send collaboration message", e);
        }
    }

    private String buildWebSocketUrl() {
        String base = baseUrl;
        if (base.startsWith("https://")) {
            base = "wss://" + base.substring(8);
        } else if (base.startsWith("http://")) {
            base = "ws://" + base.substring(7);
        } else if (!base.startsWith("ws://") && !base.startsWith("wss://")) {
            base = "wss://" + base;
        }
        return base + "/api/v1/agents/client/websocket";
    }

    private class CollaborationListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
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
            connected.set(false);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("Collaboration WS error", error);
            connected.set(false);
            scheduleReconnect();
        }
    }

    private void handleMessage(String json) {
        try {
            JsonNode envelope = mapper.readTree(json);
            String event = envelope.has("event") ? envelope.get("event").asText() : "";
            JsonNode payload = envelope.get("payload");

            switch (event) {
                case "task.dispatch" -> handleTaskDispatch(payload);
                case "connection.kicked" -> {
                    log.warn("Kicked from collaboration connection");
                    stop();
                }
                case "release.push" -> handleReleasePush(payload);
                case "pong" -> log.trace("Received pong");
                default -> log.debug("Unhandled collaboration event: {}", event);
            }
        } catch (Exception e) {
            log.error("Failed to handle collaboration message: {}", json, e);
        }
    }

    private void handleTaskDispatch(JsonNode payload) {
        if (taskHandler == null) {
            log.warn("No task handler registered, ignoring dispatch");
            return;
        }
        TaskDispatch task = new TaskDispatch();
        task.taskId = payload.has("task_id") ? payload.get("task_id").asText() : "";
        task.flowId = payload.has("flow_id") ? payload.get("flow_id").asText() : "";
        task.inputValue = payload.has("input_value") ? payload.get("input_value").asText() : "";
        task.sessionId = payload.has("session_id") ? payload.get("session_id").asText() : "";
        task.payload = payload;

        sendTaskAck(task.taskId);
        taskExecutor.submit(() -> taskHandler.accept(task));
    }

    private void handleReleasePush(JsonNode payload) {
        if (releaseHandler == null) return;
        ReleasePush release = new ReleasePush();
        release.version = payload.has("version") ? payload.get("version").asText() : "";
        release.payload = payload;
        releaseHandler.accept(release);
    }

    /**
     * Represents an incoming task dispatch.
     */
    public static class TaskDispatch {
        public String taskId;
        public String flowId;
        public String inputValue;
        public String sessionId;
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
