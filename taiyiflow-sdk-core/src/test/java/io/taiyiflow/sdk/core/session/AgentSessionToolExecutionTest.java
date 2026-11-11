package io.taiyiflow.sdk.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.state.SessionState;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionToolExecutionTest {

    private final ObjectMapper mapper = new ObjectMapper();
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
    void executesSseClientToolReportsResultAndReceivesFinalMessage() throws Exception {
        AtomicReference<JsonNode> reportedResult = new AtomicReference<>();
        server.createContext("/api/v1/run/flow-1/stream", exchange ->
                respond(exchange, 200, "text/event-stream", """
                        event: client_tool
                        data: {"function":{"id":"call-1","name":"get_weather","arguments":{"city":"Beijing"}},"context":{"session_id":"session-1","run_id":"run-1","message_id":"message-1"}}

                        event: message
                        data: {"chunk":"Sunny"}

                        event: close
                        data: {"run_finished":true}

                        """));
        server.createContext("/api/v1/agents/client/call/result", exchange -> {
            reportedResult.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, "application/json", "{\"code\":0}");
        });
        server.start();

        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl(baseUrl)
                .apiKey("test-key")
                .flowId("flow-1")
                .streamProtocol(StreamProtocol.SSE)
                .build();
        AtomicReference<ToolCall> executedCall = new AtomicReference<>();
        agent.getPluginManager().register("get_weather", call -> {
            executedCall.set(call);
            return ToolResult.success(call.getId(), mapper.createObjectNode()
                    .put("city", call.getArguments().get("city").asText())
                    .put("temperature", 22));
        });

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
            }

            @Override
            public void onClose(String sessionId, String reason) {
                closed.countDown();
            }
        });

        try {
            session.chat("What is the weather?");
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            assertEquals(List.of("Sunny"), chunks);
            assertEquals(SessionState.DONE, ((AgentSessionImpl) session).getState());

            ToolCall call = executedCall.get();
            assertEquals("call-1", call.getId());
            assertEquals("flow-1", call.getFlowId());
            assertEquals("session-1", call.getSessionId());
            assertEquals("run-1", call.getRunId());
            assertEquals("message-1", call.getMessageId());

            JsonNode body = reportedResult.get();
            assertEquals("flow-1", body.get("flow_id").asText());
            assertEquals("session-1", body.get("session_id").asText());
            assertEquals("run-1", body.get("run_id").asText());
            assertEquals("message-1", body.get("message_id").asText());
            assertEquals("call-1", body.get("call_id").asText());
            assertTrue(body.get("success").asBoolean());
            assertEquals("Beijing", body.get("result").get("city").asText());
            assertEquals(22, body.get("result").get("temperature").asInt());
            assertTrue(body.get("error_message").isNull());
        } finally {
            agent.shutdown();
        }
    }

    @Test
    void errorEventResultsInErrorState() throws Exception {
        server.createContext("/api/v1/run/flow-1/stream", exchange ->
                respond(exchange, 200, "text/event-stream", """
                        event: error
                        data: {"error":{"message":"backend failed"}}

                        event: close
                        data: {"run_finished":false}

                        """));
        server.start();
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl(baseUrl).apiKey("test-key").flowId("flow-1")
                .streamProtocol(StreamProtocol.SSE).build();
        try {
            AgentSession session = agent.newSession("session-1");
            AtomicReference<Throwable> error = new AtomicReference<>();
            session.onStream(new StreamHook() {
                @Override
                public void onError(String sessionId, Throwable cause) {
                    error.set(cause);
                }
            });

            session.chat("hello");

            assertTrue(session.await(Duration.ofSeconds(5)));
            assertEquals(SessionState.ERROR, session.getState());
            assertTrue(error.get().getMessage().contains("backend failed"));
        } finally {
            agent.shutdown();
        }
    }

    @Test
    void eofWithoutCloseEventIsDisconnected() throws Exception {
        server.createContext("/api/v1/run/flow-1/stream", exchange ->
                respond(exchange, 200, "text/event-stream", """
                        event: message
                        data: {"chunk":"incomplete"}

                        """));
        server.start();
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl(baseUrl).apiKey("test-key").flowId("flow-1")
                .streamProtocol(StreamProtocol.SSE).build();
        try {
            AgentSession session = agent.newSession("session-1").chat("hello");

            assertTrue(session.await(Duration.ofSeconds(5)));
            assertEquals(SessionState.DISCONNECTED, session.getState());
        } finally {
            agent.shutdown();
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
