package io.taiyiflow.sdk.core.session;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.state.SessionState;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.transport.envelope.LicenseExpiredException;
import io.taiyiflow.sdk.transport.rest.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * License expiry must surface as a distinct, non-recoverable terminal error (no retry storm),
 * via both backend channels: the HTTP 403 entry rejection (REST/SSE) and the in-stream
 * {@code error} event carrying {@code data.type}. WS and SSE share the same event handler.
 */
class AgentSessionLicenseExpiryTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/api/v1/config", exchange -> respond(exchange, 200, "application/json", "{}"));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sseEntry403ExpiredDetailBecomesTerminalLicenseError() throws Exception {
        server.createContext("/api/v1/run/flow-1/stream", exchange ->
                respond(exchange, 403, "application/json",
                        "{\"detail\":\"" + LicenseExpiredException.EXPIRED_DETAIL + "\"}"));
        server.start();
        Result result = run();
        assertTrue(result.terminal.await(5, TimeUnit.SECONDS));
        assertEquals(SessionState.ERROR, result.state());
        assertTrue(result.error.get() instanceof LicenseExpiredException);
        LicenseExpiredException license = (LicenseExpiredException) result.error.get();
        assertEquals(LicenseExpiredException.ERROR_CODE, license.getCode());
        assertEquals(LicenseExpiredException.EXPIRED_DETAIL, license.getMessage());
        result.agent.shutdown();
    }

    @Test
    void sseEntry403NotStartedDetailBecomesTerminalLicenseError() throws Exception {
        server.createContext("/api/v1/run/flow-1/stream", exchange ->
                respond(exchange, 403, "application/json",
                        "{\"detail\":\"" + LicenseExpiredException.NOT_STARTED_DETAIL + "\"}"));
        server.start();
        Result result = run();
        assertTrue(result.terminal.await(5, TimeUnit.SECONDS));
        assertEquals(SessionState.ERROR, result.state());
        assertTrue(result.error.get() instanceof LicenseExpiredException);
        assertEquals(LicenseExpiredException.NOT_STARTED_DETAIL, result.error.get().getMessage());
        result.agent.shutdown();
    }

    @Test
    void inStreamLicenseErrorEventBecomesTerminalLicenseError() throws Exception {
        server.createContext("/api/v1/run/flow-1/stream", exchange ->
                respond(exchange, 200, "text/event-stream",
                        "event: error\ndata: {\"error\":\"" + LicenseExpiredException.EXPIRED_DETAIL
                                + "\",\"type\":\"license_expired\",\"persisted\":false}\n\n"));
        server.start();
        Result result = run();
        assertTrue(result.terminal.await(5, TimeUnit.SECONDS));
        assertEquals(SessionState.ERROR, result.state());
        assertTrue(result.error.get() instanceof LicenseExpiredException);
        assertEquals(LicenseExpiredException.ERROR_CODE, ((LicenseExpiredException) result.error.get()).getCode());
        result.agent.shutdown();
    }

    @Test
    void inStreamTerminalLimitErrorEventIsLicenseFamily() throws Exception {
        server.createContext("/api/v1/run/flow-1/stream", exchange ->
                respond(exchange, 200, "text/event-stream",
                        "event: error\ndata: {\"error\":\"在线终端数超限\",\"type\":\"license_terminal_limit\",\"persisted\":false}\n\n"));
        server.start();
        Result result = run();
        assertTrue(result.terminal.await(5, TimeUnit.SECONDS));
        assertEquals(SessionState.ERROR, result.state());
        assertTrue(result.error.get() instanceof LicenseExpiredException);
        assertEquals(LicenseExpiredException.TERMINAL_LIMIT_CODE,
                ((LicenseExpiredException) result.error.get()).getCode());
        result.agent.shutdown();
    }

    @Test
    void nonLicense403StaysGenericRestError() throws Exception {
        server.createContext("/api/v1/run/flow-1/stream", exchange ->
                respond(exchange, 403, "application/json", "{\"detail\":\"无权访问此智能服务应用\"}"));
        server.start();
        Result result = run();
        assertTrue(result.terminal.await(5, TimeUnit.SECONDS));
        assertEquals(SessionState.ERROR, result.state());
        assertTrue(result.error.get() instanceof RestClient.RestException);
        assertFalse(result.error.get() instanceof LicenseExpiredException);
        result.agent.shutdown();
    }

    @Test
    void nonLicenseErrorEventStaysGeneric() throws Exception {
        server.createContext("/api/v1/run/flow-1/stream", exchange ->
                respond(exchange, 200, "text/event-stream",
                        "event: error\ndata: {\"error\":\"配额不足\",\"type\":\"quota_session_exceeded\",\"persisted\":false}\n\n"));
        server.start();
        Result result = run();
        assertTrue(result.terminal.await(5, TimeUnit.SECONDS));
        assertEquals(SessionState.ERROR, result.state());
        assertFalse(result.error.get() instanceof LicenseExpiredException);
        assertTrue(result.error.get().getMessage().contains("配额不足"));
        result.agent.shutdown();
    }

    private Result run() {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl(baseUrl)
                .apiKey("test-key")
                .flowId("flow-1")
                .streamProtocol(StreamProtocol.SSE)
                .build();
        AgentSession session = agent.newSession("session-1");
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        session.onStream(new StreamHook() {
            @Override
            public void onError(String sessionId, Throwable cause) {
                error.set(cause);
                terminal.countDown();
            }

            @Override
            public void onClose(String sessionId, String reason) {
                terminal.countDown();
            }
        });
        session.chat("hello");
        return new Result(agent, session, terminal, error);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Result(TaiyiAgent agent, AgentSession session, CountDownLatch terminal,
                          AtomicReference<Throwable> error) {
        SessionState state() {
            return ((AgentSessionImpl) session).getState();
        }
    }
}