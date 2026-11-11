package io.taiyiflow.sdk.core.session;

import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.hooks.StreamHook;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionSseFallbackTest {

    @Test
    void fallsBackOnlyWhenWebSocketConnectionFailsBeforeFirstFrame() throws Exception {
        AtomicBoolean webSocketAttempted = new AtomicBoolean();
        AtomicBoolean sseRequested = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/run/flow-1/websocket", exchange -> {
            webSocketAttempted.set(true);
            exchange.sendResponseHeaders(501, -1);
            exchange.close();
        });
        server.createContext("/api/v1/run/flow-1/stream", exchange -> {
            sseRequested.set(true);
            byte[] body = "event: message\ndata: {\"chunk\":\"fallback\"}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("test-key")
                .flowId("flow-1")
                .streamProtocol(StreamProtocol.WEBSOCKET)
                .build();
        AgentSession session = agent.newSession("session-1");
        List<String> chunks = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        session.onStream(new StreamHook() {
            @Override
            public void onChunk(String sessionId, String chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onError(String sessionId, Throwable cause) {
                error.set(cause);
            }
        });

        try {
            session.chat("hello");
            assertTrue(session.await(Duration.ofSeconds(5)));
            assertTrue(webSocketAttempted.get());
            assertTrue(sseRequested.get());
            assertEquals(List.of("fallback"), chunks);
            assertNull(error.get());
        } finally {
            agent.shutdown();
            server.stop(0);
        }
    }
}
