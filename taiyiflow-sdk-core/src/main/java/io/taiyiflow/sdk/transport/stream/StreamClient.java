package io.taiyiflow.sdk.transport.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import io.taiyiflow.sdk.transport.envelope.CloseCodes;
import io.taiyiflow.sdk.transport.envelope.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * WebSocket streaming client for agent runs.
 * Connects to /api/v1/run/{flowId}/websocket and handles bidirectional communication.
 */
public class StreamClient {

    private static final Logger log = LoggerFactory.getLogger(StreamClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long FIRST_MESSAGE_TIMEOUT_MS = 10_000;

    private final String baseUrl;
    private final String apiKey;
    private final String flowId;
    private final HttpClient httpClient;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private WebSocket webSocket;

    public StreamClient(String baseUrl, String apiKey, String flowId) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.flowId = flowId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Start a streaming run.
     *
     * @param request the initial chat request
     * @param onEvent callback for each stream event
     * @param onClose callback when the stream closes
     * @param onError callback on error
     * @return a handle to control the stream
     */
    public StreamHandle stream(
            ChatRequest request,
            Consumer<StreamEvent> onEvent,
            Consumer<String> onClose,
            Consumer<Throwable> onError) {

        String wsUrl = buildWebSocketUrl();
        log.debug("Connecting to: {}", wsUrl);

        CountDownLatch readyLatch = new CountDownLatch(1);
        StreamHandle handle = new StreamHandle();

        CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + apiKey)
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    private final StringBuilder textBuffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        log.debug("WebSocket opened for session: {}", request.getSessionId());
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        textBuffer.append(data);
                        if (last) {
                            String message = textBuffer.toString();
                            textBuffer.setLength(0);
                            handleMessage(message, onEvent, onError);
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        log.warn("Received unexpected binary frame");
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        log.debug("WebSocket closed: code={}, reason={}", statusCode, reason);
                        String closeReason = CloseCodes.describe(statusCode);
                        if (reason != null && !reason.isEmpty()) {
                            closeReason = closeReason + ": " + reason;
                        }
                        onClose.accept(closeReason);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        log.error("WebSocket error", error);
                        onError.accept(error);
                    }
                });

        wsFuture.thenAccept(ws -> {
            this.webSocket = ws;
            handle.webSocket = ws;
            try {
                String json = mapper.writeValueAsString(request);
                ws.sendText(json, true);
                log.debug("Sent initial request for session: {}", request.getSessionId());
                readyLatch.countDown();
            } catch (Exception e) {
                onError.accept(e);
                readyLatch.countDown();
            }
        }).exceptionally(ex -> {
            onError.accept(ex);
            readyLatch.countDown();
            return null;
        });

        return handle;
    }

    private void handleMessage(String json, Consumer<StreamEvent> onEvent, Consumer<Throwable> onError) {
        try {
            StreamEvent event = mapper.readValue(json, StreamEvent.class);
            onEvent.accept(event);
        } catch (Exception e) {
            log.warn("Failed to parse stream event: {}", json, e);
            onError.accept(new StreamParseException("Failed to parse event: " + json, e));
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
        return base + "/api/v1/run/" + flowId + "/websocket";
    }

    /**
     * Handle for controlling an active stream.
     */
    public static class StreamHandle {
        private volatile WebSocket webSocket;

        /**
         * Send a tool result back to the agent.
         */
        public void sendToolResult(String toolCallId, Object result) {
            if (webSocket == null) {
                throw new IllegalStateException("Stream not connected");
            }
            try {
                Map<String, Object> payload = Map.of(
                        "event", "tool_result",
                        "data", Map.of(
                                "tool_call_id", toolCallId,
                                "result", result
                        )
                );
                String json = mapper.writeValueAsString(payload);
                webSocket.sendText(json, true);
            } catch (Exception e) {
                log.error("Failed to send tool result", e);
            }
        }

        /**
         * Cancel/interrupt the stream.
         */
        public void cancel() {
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "cancelled");
            }
        }

        /**
         * Abort the stream immediately.
         */
        public void abort() {
            if (webSocket != null) {
                webSocket.abort();
            }
        }
    }

    public static class StreamParseException extends RuntimeException {
        public StreamParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
