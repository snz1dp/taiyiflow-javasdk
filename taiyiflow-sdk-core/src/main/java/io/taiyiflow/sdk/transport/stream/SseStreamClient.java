package io.taiyiflow.sdk.transport.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import io.taiyiflow.sdk.transport.envelope.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * SSE (Server-Sent Events) fallback client for streaming.
 * Used when WebSocket is not available.
 * POST /run/{flowId}/stream
 */
public class SseStreamClient {

    private static final Logger log = LoggerFactory.getLogger(SseStreamClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String baseUrl;
    private final Supplier<String> accessToken;
    private final String flowId;
    private final Map<String, String> credentialHeaders;
    private final HttpClient httpClient;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SseStreamClient(String baseUrl, String apiKey, String flowId) {
        this(baseUrl, apiKey, flowId, Map.of());
    }

    public SseStreamClient(String baseUrl, String apiKey, String flowId, Map<String, String> credentialHeaders) {
        this(baseUrl, () -> apiKey, flowId, credentialHeaders);
    }

    public SseStreamClient(String baseUrl, Supplier<String> accessToken, String flowId,
                           Map<String, String> credentialHeaders) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.accessToken = accessToken;
        this.flowId = flowId;
        this.credentialHeaders = Map.copyOf(credentialHeaders);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Start an SSE stream.
     */
    public void stream(
            ChatRequest request,
            Consumer<StreamEvent> onEvent,
            Consumer<String> onClose,
            Consumer<Throwable> onError) {
        stream(request, onEvent, onClose, onError, ignored -> {
        });
    }

    public void stream(
            ChatRequest request,
            Consumer<StreamEvent> onEvent,
            Consumer<String> onClose,
            Consumer<Throwable> onError,
            Consumer<String> onSessionId) {

        executor.submit(() -> {
            try {
                String url = baseUrl + "/api/v1/run/" + flowId + "/stream";
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + accessToken.get())
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream");
                credentialHeaders.forEach(requestBuilder::header);
                HttpRequest httpRequest = requestBuilder
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request)))
                        .build();

                HttpResponse<java.io.InputStream> response = httpClient.send(
                        httpRequest, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() >= 400) {
                    response.body().close();
                    onError.accept(new RuntimeException("SSE request failed: " + response.statusCode()));
                    return;
                }
                response.headers().firstValue("X-TaiyiFlow-Session-id")
                        .filter(value -> !value.isBlank())
                        .ifPresent(onSessionId);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    StringBuilder eventData = new StringBuilder();
                    String eventType = null;

                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (eventData.length() > 0) {
                                processSseEvent(eventType, eventData.toString(), onEvent);
                                eventData.setLength(0);
                                eventType = null;
                            }
                        } else if (line.startsWith("event:")) {
                            eventType = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            if (eventData.length() > 0) {
                                eventData.append("\n");
                            }
                            eventData.append(line.substring(5).trim());
                        }
                    }
                }
                if (!closed.get()) {
                    onClose.accept("Stream ended");
                }
            } catch (Exception e) {
                if (!closed.get()) {
                    log.error("SSE stream error", e);
                    onError.accept(e);
                }
            } finally {
                executor.shutdown();
            }
        });
    }

    private void processSseEvent(String eventType, String data, Consumer<StreamEvent> onEvent) {
        try {
            StreamEvent event;
            if (eventType != null) {
                event = new StreamEvent();
                event.setEvent(eventType);
                event.setData(mapper.readTree(data));
            } else {
                event = mapper.readValue(data, StreamEvent.class);
            }
            onEvent.accept(event);
        } catch (Exception e) {
            log.warn("Failed to parse SSE event: type={}, data={}", eventType, data, e);
        }
    }

    public void close() {
        closed.set(true);
        executor.shutdownNow();
    }
}
