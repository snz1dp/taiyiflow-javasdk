package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.RecoveryPolicy;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.collaboration.CollaborationPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionTaskHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void collectsResultAndReusesResolvedSessionWithLatestToken() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        List<String> authorizations = new CopyOnWriteArrayList<>();
        AtomicInteger turn = new AtomicInteger();
        server.createContext("/api/v1/run/flow-1/stream", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            int currentTurn = turn.incrementAndGet();
            if (currentTurn == 1) {
                exchange.getResponseHeaders().set("X-TaiyiFlow-Session-id", "server-session");
            }
            respond(exchange, 200, "event: message\ndata: {\"chunk\":\"result-" + currentTurn
                    + "\",\"run_id\":\"run-" + currentTurn + "\"}\n\n"
                    + "event: close\ndata: {\"run_finished\":true}\n\n");
        });
        server.start();
        AtomicReference<String> token = new AtomicReference<>("old-token");
        AtomicInteger agents = new AtomicInteger();
        try (AgentSessionTaskHandler handler = new AgentSessionTaskHandler(flowId -> {
            agents.incrementAndGet();
            return agent(flowId, token::get);
        })) {
            ObjectNode first = (ObjectNode) handler.execute(task("first"), progress -> { });
            token.set("new-token");
            ObjectNode second = (ObjectNode) handler.execute(task("second"), progress -> { });

            assertEquals("result-1", first.required("text").asText());
            assertEquals("run-1", first.required("run_id").asText());
            assertEquals("server-session", first.required("session_id").asText());
            assertEquals("result-2", second.required("text").asText());
            assertEquals("run-2", second.required("run_id").asText());
            assertEquals(1, agents.get());
            assertEquals(List.of("Bearer old-token", "Bearer new-token"), authorizations);
            assertEquals("client-session", requests.get(0).required("session_id").asText());
            assertEquals("server-session", requests.get(1).required("session_id").asText());
            assertEquals("second", requests.get(1).required("input_value").asText());
        }
    }

    @Test
    void protocolErrorIsNotReportedAsSuccessfulResult() throws Exception {
        server.createContext("/api/v1/run/flow-1/stream", exchange ->
                respond(exchange, 200, "event: error\ndata: {\"error\":\"boom\"}\n\n"));
        server.start();

        try (AgentSessionTaskHandler handler = new AgentSessionTaskHandler(
                flowId -> agent(flowId, () -> "token"))) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> handler.execute(task("run"), progress -> { }));
            assertTrue(error.getMessage().contains("boom"));
        }
    }

    private TaiyiAgent agent(String flowId, java.util.function.Supplier<String> token) {
        return TaiyiAgent.builder()
                .baseUrl(baseUrl)
                .accessToken(token)
                .flowId(flowId)
                .streamProtocol(StreamProtocol.SSE)
                .recoveryPolicy(RecoveryPolicy.MANUAL)
                .build();
    }

    private static CollaborationPlugin.TaskDispatch task(String input) {
        CollaborationPlugin.TaskDispatch task = new CollaborationPlugin.TaskDispatch();
        task.taskKind = "remote_task";
        task.taskType = "task";
        task.flowId = "flow-1";
        task.sessionId = "client-session";
        task.inputValue = input;
        return task;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
