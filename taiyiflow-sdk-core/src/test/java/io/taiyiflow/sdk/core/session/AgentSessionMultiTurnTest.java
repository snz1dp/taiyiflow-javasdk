package io.taiyiflow.sdk.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.state.SessionState;
import io.taiyiflow.sdk.hooks.StreamHook;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentSessionMultiTurnTest {

    @Test
    void reusesResolvedSessionForSecondTurnAndSendsExplicitFiles() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        AtomicInteger turn = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/run/flow-1/stream", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            int currentTurn = turn.incrementAndGet();
            if (currentTurn == 1) {
                exchange.getResponseHeaders().set("X-TaiyiFlow-Session-id", "server-session");
            }
            String chunk = currentTurn == 1 ? "First" : "Second";
            byte[] body = ("event: message\ndata: {\"chunk\":\"" + chunk + "\"}\n\n"
                    + "event: close\ndata: {\"run_finished\":true}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
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
                .streamProtocol(StreamProtocol.SSE)
                .flowAdapter(request -> request.setMessageId("message-" + request.getInputValue()))
                .build();
        AgentSession session = agent.newSession("client-session");
        List<String> chunks = new CopyOnWriteArrayList<>();
        session.onStream(new StreamHook() {
            @Override
            public void onChunk(String sessionId, String chunk) {
                chunks.add(chunk);
            }
        });

        try {
            session.chat("first");
            session.await();
            assertEquals("server-session", session.getSessionId());
            assertEquals(SessionState.DONE, ((AgentSessionImpl) session).getState());

            session.chat("second", List.of("uploads/input.txt", "uploads/data.json"));
            session.await();

            assertEquals(2, requests.size());
            assertEquals("client-session", requests.get(0).required("session_id").asText());
            assertEquals("first", requests.get(0).required("input_value").asText());
            assertEquals("message-first", requests.get(0).required("message_id").asText());
            assertEquals("server-session", requests.get(1).required("session_id").asText());
            assertEquals("second", requests.get(1).required("input_value").asText());
            assertEquals("message-second", requests.get(1).required("message_id").asText());
            assertEquals("uploads/input.txt", requests.get(1).required("files").get(0).asText());
            assertEquals("uploads/data.json", requests.get(1).required("files").get(1).asText());
            assertEquals(List.of("First", "Second"), chunks);
            assertEquals(SessionState.DONE, ((AgentSessionImpl) session).getState());
        } finally {
            agent.shutdown();
            server.stop(0);
        }
    }
}
