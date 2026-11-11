package io.taiyiflow.sdk.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.state.SessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentSessionRetryRepeatTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Queue<String> streamEvents = new ConcurrentLinkedQueue<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch initialRelease = new CountDownLatch(1);
    private final AtomicInteger cancelCalls = new AtomicInteger();

    private HttpServer server;
    private ExecutorService serverExecutor;
    private TaiyiAgent agent;
    private volatile boolean holdFirstStream;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/api/v1/run/flow-1/stream", this::handleStream);
        server.createContext("/api/v1/run/sessions/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            initialRelease.countDown();
            if (exchange.getRequestURI().getPath().endsWith("/cancel")) {
                cancelCalls.incrementAndGet();
                respond(exchange, 202, "{\"run_id\":\"run-1\",\"status\":\"cancel_requested\"}");
            } else {
                respond(exchange, 202,
                        "{\"run_id\":\"run-1\",\"status\":\"paused\",\"checkpoint_id\":\"checkpoint-1\"}");
            }
        });
        server.start();
        agent = TaiyiAgent.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key")
                .flowId("flow-1")
                .clientId("client-1")
                .streamProtocol(StreamProtocol.SSE)
                .build();
    }

    @AfterEach
    void tearDown() {
        initialRelease.countDown();
        if (agent != null) {
            agent.shutdown();
        }
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void retryReplaysOriginalInputBeforePersistence() throws Exception {
        streamEvents.add("event: message\ndata: {\"chunk\":\"failed\",\"run_id\":\"run-1\"}\n\n");
        streamEvents.add("event: close\ndata: {\"run_finished\":true}\n\n");
        AgentSession session = agent.newSession("session-1").chat("original");
        assertTrue(session.await(Duration.ofSeconds(5)));

        session.retry();

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals("original", requests.get(1).required("input_value").asText());
        assertNewExecutionIdentity(requests.get(0), requests.get(1));
    }

    @Test
    void retryUsesContinuationAndOriginalTemplateAfterPersistence() throws Exception {
        streamEvents.add("""
                event: message
                data: {"chunk":"done","run_id":"run-1","event_id":"event-1"}

                """);
        streamEvents.add("event: close\ndata: {\"run_finished\":true}\n\n");
        AgentSession session = agent.newSession("session-1");
        session.chat("original", Map.of("node", mapper.createObjectNode().put("value", 1)),
                List.of("uploads/input.txt"));
        assertTrue(session.await(Duration.ofSeconds(5)));

        session.retry();

        assertTrue(session.await(Duration.ofSeconds(5)));
        JsonNode retry = requests.get(1);
        assertEquals("请继续完成上面的任务", retry.required("input_value").asText());
        assertEquals(1, retry.required("tweaks").required("node").required("value").asInt());
        assertEquals("uploads/input.txt", retry.required("files").get(0).asText());
        assertNewExecutionIdentity(requests.get(0), retry);
    }

    @Test
    void repeatPausedRunCancelsItAndReusesOriginalInput() throws Exception {
        holdFirstStream = true;
        streamEvents.add("""
                event: message
                data: {"chunk":"working","run_id":"run-1","event_id":"event-1"}

                """);
        streamEvents.add("event: close\ndata: {\"run_finished\":true}\n\n");
        AgentSession session = agent.newSession("session-1").chat("repeat me");
        awaitRun(session);
        session.pause();

        session.repeat();

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.DONE, session.getState());
        assertEquals(1, cancelCalls.get());
        assertEquals("repeat me", requests.get(1).required("input_value").asText());
        assertNewExecutionIdentity(requests.get(0), requests.get(1));
    }

    @Test
    void activeSessionCannotRetryOrRepeat() throws Exception {
        holdFirstStream = true;
        streamEvents.add("""
                event: message
                data: {"chunk":"working","run_id":"run-1","event_id":"event-1"}

                """);
        AgentSession session = agent.newSession("session-1").chat("working");
        awaitRun(session);

        assertThrows(IllegalStateException.class, session::retry);
        assertThrows(IllegalStateException.class, session::repeat);
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        requests.add(mapper.readTree(exchange.getRequestBody()));
        String events = streamEvents.poll();
        if (events == null) {
            respond(exchange, 500, "{}");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(events.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
        if (holdFirstStream && requests.size() == 1) {
            try {
                initialRelease.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        exchange.close();
    }

    private static void awaitRun(AgentSession session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!"run-1".equals(session.getRunContext().getRunId()) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("run-1", session.getRunContext().getRunId());
    }

    private static void assertNewExecutionIdentity(JsonNode original, JsonNode repeated) {
        assertEquals("start", repeated.required("execution_mode").asText());
        assertNotEquals(original.required("message_id").asText(), repeated.required("message_id").asText());
        assertNotEquals(original.required("subscription_id").asText(),
                repeated.required("subscription_id").asText());
        assertNotEquals("run-1", repeated.required("run_id").asText());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
