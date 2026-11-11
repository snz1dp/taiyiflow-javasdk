package io.taiyiflow.sdk.core.session;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.state.SessionState;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentSessionExecutionControlTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private TaiyiAgent agent;
    private final CountDownLatch streamRelease = new CountDownLatch(1);
    private final CountDownLatch pauseEntered = new CountDownLatch(1);
    private final CountDownLatch pauseRelease = new CountDownLatch(1);
    private volatile boolean blockPause;
    private volatile int pauseStatus = 202;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/api/v1/run/flow-1/stream", this::handleStream);
        server.createContext("/api/v1/run/sessions/", this::handleControl);
        server.start();
        agent = TaiyiAgent.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key")
                .flowId("flow-1")
                .clientId("client-1")
                .deviceId("device-1")
                .streamProtocol(StreamProtocol.SSE)
                .build();
    }

    @AfterEach
    void tearDown() {
        streamRelease.countDown();
        pauseRelease.countDown();
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
    void pauseIsMutuallyExclusiveWithChatAndFreezesSession() throws Exception {
        blockPause = true;
        AgentSession session = agent.newSession("session-1").chat("hello");
        awaitRun(session);
        ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> pause = caller.submit(() -> {
                try {
                    session.pause();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(pauseEntered.await(2, TimeUnit.SECONDS));
            assertEquals(SessionState.PAUSING, session.getState());
            assertThrows(IllegalStateException.class, () -> session.chat("concurrent"));
            pauseRelease.countDown();
            pause.get(2, TimeUnit.SECONDS);

            assertEquals(SessionState.PAUSED, session.getState());
            assertFalse(session.isActive());
            assertTrue(session.await(Duration.ofSeconds(1)));
            assertEquals("run-1", session.getRunContext().getRunId());
            assertEquals("attempt-1", session.getRunContext().getExecutionAttemptId());
            assertEquals("event-1", session.getRunContext().getAfterEventId());
            assertThrows(IllegalStateException.class, () -> session.chat("still paused"));
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void failedPauseRestoresStreamingState() throws Exception {
        pauseStatus = 409;
        AgentSession session = agent.newSession("session-1").chat("hello");
        awaitRun(session);

        assertThrows(Exception.class, session::pause);
        assertEquals(SessionState.STREAMING, session.getState());
        session.interrupt();
        assertEquals(SessionState.INTERRUPTED, session.getState());
    }

    @Test
    void transportCloseAloneDoesNotConfirmPause() throws Exception {
        blockPause = true;
        AgentSession session = agent.newSession("session-1").chat("hello");
        awaitRun(session);
        ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> pause = caller.submit(() -> {
                try {
                    session.pause();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(pauseEntered.await(2, TimeUnit.SECONDS));
            assertEquals(SessionState.PAUSING, session.getState());

            streamRelease.countDown();
            awaitState(session, SessionState.DISCONNECTED);

            assertTrue(session.await(Duration.ofSeconds(1)));
            assertEquals(SessionState.DISCONNECTED, session.getState());
            pauseRelease.countDown();
            pause.get(2, TimeUnit.SECONDS);
            assertEquals(SessionState.PAUSED, session.getState());
        } finally {
            streamRelease.countDown();
            pauseRelease.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void cancelTerminatesSessionAndIgnoresLateStreamClose() throws Exception {
        AgentSession session = agent.newSession("session-1").chat("hello");
        awaitRun(session);

        var result = session.cancel();

        assertEquals("cancel_requested", result.getStatus());
        assertEquals(SessionState.CANCELLED, session.getState());
        assertFalse(session.isActive());
        assertTrue(session.await(Duration.ofSeconds(1)));
        assertEquals("run-1", session.getRunContext().getRunId());
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        if (!"/api/v1/run/flow-1/stream".equals(exchange.getRequestURI().getPath())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("X-TaiyiFlow-Session-id", "session-1");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(("event: message\n"
                + "data: {\"chunk\":\"working\",\"run_id\":\"run-1\","
                + "\"execution_attempt_id\":\"attempt-1\",\"event_id\":\"event-1\"}\n\n")
                .getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
        try {
            streamRelease.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private void handleControl(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/pause")) {
            pauseEntered.countDown();
            if (blockPause) {
                try {
                    pauseRelease.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (pauseStatus != 202) {
                exchange.sendResponseHeaders(pauseStatus, -1);
                exchange.close();
                return;
            }
            streamRelease.countDown();
            respond(exchange, 202, "{\"session_id\":\"session-1\",\"run_id\":\"run-1\","
                    + "\"status\":\"paused\",\"request_id\":\"request-1\",\"checkpoint_id\":\"checkpoint-1\"}");
        } else if (path.endsWith("/cancel")) {
            streamRelease.countDown();
            respond(exchange, 202, "{\"session_id\":\"session-1\",\"run_id\":\"run-1\","
                    + "\"status\":\"cancel_requested\",\"request_id\":\"request-2\"}");
        } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void awaitRun(AgentSession session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (session.getRunContext().getRunId() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("run-1", session.getRunContext().getRunId());
    }

    private static void awaitState(AgentSession session, SessionState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (session.getState() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(expected, session.getState());
    }
}
