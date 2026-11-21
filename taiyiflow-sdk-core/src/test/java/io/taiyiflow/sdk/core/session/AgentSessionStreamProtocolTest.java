package io.taiyiflow.sdk.core.session;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.state.SessionState;
import io.taiyiflow.sdk.hooks.StreamHook;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionStreamProtocolTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void autoUsesServerSseProtocolAndCredentialHeaders() throws Exception {
        AtomicInteger configCalls = new AtomicInteger();
        AtomicReference<String> configCredential = new AtomicReference<>();
        AtomicReference<String> streamCredential = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/v1/config", exchange -> {
            configCalls.incrementAndGet();
            configCredential.set(exchange.getRequestHeaders().getFirst("X-Credential-Username"));
            respond(exchange, 200, "application/json", "{\"stream_protocol\":\"sse\"}");
        });
        server.createContext("/api/v1/run/flow-1/stream", exchange -> {
            streamCredential.set(exchange.getRequestHeaders().getFirst("X-Credential-Username"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "text/event-stream",
                    "event: message\ndata: {\"chunk\":\"hello\"}\n\n"
                            + "event: close\ndata: {\"run_finished\":true}\n\n");
        });
        server.start();

        SessionResult result = runSession(StreamProtocol.AUTO);

        assertTrue(result.closed.await(5, TimeUnit.SECONDS));
        assertNull(result.error.get());
        assertEquals(List.of("hello"), result.chunks);
        assertEquals(SessionState.DONE, ((AgentSessionImpl) result.session).getState());
        // One fetch for protocol resolution, one for the session-concurrency quota; both cached per agent.
        assertEquals(2, configCalls.get());
        assertEquals("worker", configCredential.get());
        assertEquals("worker", streamCredential.get());
        assertEquals("Bearer test-key", authorization.get());
        assertTrue(requestBody.get().contains("\"session_id\""));
        assertTrue(requestBody.get().contains("\"input_value\":\"hello\""));
        result.agent.shutdown();
    }

    @Test
    void explicitSseSkipsProtocolResolutionButStillFetchesQuotaOnce() throws Exception {
        AtomicInteger configCalls = new AtomicInteger();
        server.createContext("/api/v1/config", exchange -> {
            configCalls.incrementAndGet();
            respond(exchange, 500, "application/json", "{}");
        });
        server.createContext("/api/v1/run/flow-1/stream", exchange ->
                respond(exchange, 200, "text/event-stream",
                        "event: message\ndata: {\"chunk\":\"explicit\"}\n\n"
                                + "event: close\ndata: {\"run_finished\":true}\n\n"));
        server.start();

        SessionResult result = runSession(StreamProtocol.SSE);

        assertTrue(result.closed.await(5, TimeUnit.SECONDS));
        assertNull(result.error.get());
        assertEquals(List.of("explicit"), result.chunks);
        // Quota fetch only (fails with 500 -> unlimited); protocol resolution never touches /config.
        assertEquals(1, configCalls.get());
        result.agent.shutdown();
    }

    @Test
    void terminalDoneNotifiesPluginSessionFinish() throws Exception {
        server.createContext("/api/v1/config", exchange ->
                respond(exchange, 200, "application/json", "{\"stream_protocol\":\"sse\"}"));
        server.createContext("/api/v1/run/flow-1/stream", exchange ->
                respond(exchange, 200, "text/event-stream",
                        "event: message\ndata: {\"chunk\":\"hello\"}\n\n"
                                + "event: close\ndata: {\"run_finished\":true}\n\n"));
        server.start();
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl(baseUrl)
                .apiKey("test-key")
                .flowId("flow-1")
                .streamProtocol(StreamProtocol.SSE)
                .credentialHeaders(Map.of("X-Credential-Username", "worker"))
                .build();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> finishedSession = new AtomicReference<>();
        agent.getPluginManager().registerSessionHook(new io.taiyiflow.sdk.hooks.SessionHook() {
            @Override
            public void onFinish(String sessionId) {
                finishedSession.set(sessionId);
                finished.countDown();
            }
        });
        AgentSession session = agent.newSession("session-1");
        session.chat("hello");

        assertTrue(finished.await(5, TimeUnit.SECONDS), "DONE must notify plugin session hooks");
        assertEquals("session-1", finishedSession.get());
        assertEquals(SessionState.DONE, ((AgentSessionImpl) session).getState());
        agent.shutdown();
    }

    private SessionResult runSession(StreamProtocol protocol) {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl(baseUrl)
                .apiKey("test-key")
                .flowId("flow-1")
                .streamProtocol(protocol)
                .credentialHeaders(Map.of("X-Credential-Username", "worker"))
                .build();
        AgentSession session = agent.newSession("session-1");
        List<String> chunks = new CopyOnWriteArrayList<>();
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        session.onStream(new StreamHook() {
            @Override
            public void onChunk(String sessionId, String chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onError(String sessionId, Throwable cause) {
                error.set(cause);
                closed.countDown();
            }

            @Override
            public void onClose(String sessionId, String reason) {
                closed.countDown();
            }
        });
        session.chat("hello");
        return new SessionResult(agent, session, chunks, closed, error);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record SessionResult(
            TaiyiAgent agent,
            AgentSession session,
            List<String> chunks,
            CountDownLatch closed,
            AtomicReference<Throwable> error) {
    }
}
