package io.taiyiflow.sdk.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.state.SessionState;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.hooks.StreamHook;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentSessionApprovalTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final CountDownLatch toolEvent = new CountDownLatch(1);
    private final CountDownLatch streamRelease = new CountDownLatch(1);
    private final List<JsonNode> reportedResults = new CopyOnWriteArrayList<>();
    private final AtomicInteger executions = new AtomicInteger();

    private HttpServer server;
    private ExecutorService serverExecutor;
    private TaiyiAgent agent;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/api/v1/run/flow-1/stream", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(("event: client_tool\n"
                    + "data: {\"function\":{\"id\":\"call-1\",\"name\":\"write_file\","
                    + "\"arguments\":{\"path\":\"/tmp/result.txt\"}},"
                    + "\"context\":{\"session_id\":\"session-1\",\"run_id\":\"run-1\","
                    + "\"message_id\":\"message-1\"}}\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                streamRelease.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.getResponseBody().write(
                    "event: close\ndata: {\"run_finished\":true}\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            exchange.close();
        });
        server.createContext("/api/v1/agents/client/call/result", exchange -> {
            reportedResults.add(mapper.readTree(exchange.getRequestBody()));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            streamRelease.countDown();
            exchange.close();
        });
        server.start();
        agent = TaiyiAgent.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key")
                .flowId("flow-1")
                .streamProtocol(StreamProtocol.SSE)
                .permissionPolicy(PermissionPolicy.CONFIRM)
                .build();
        agent.getPluginManager().register("write_file", call -> {
            executions.incrementAndGet();
            return ToolResult.success(call.getId(), mapper.createObjectNode().put("written", true));
        });
    }

    @AfterEach
    void tearDown() {
        streamRelease.countDown();
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
    void approvalExecutesToolAndReportsSuccess() throws Exception {
        AgentSession session = newSession();

        assertTrue(toolEvent.await(2, TimeUnit.SECONDS));
        assertEquals(0, executions.get());
        awaitDecision(session, true);

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.DONE, session.getState());
        assertEquals(1, executions.get());
        assertEquals(1, reportedResults.size());
        assertTrue(reportedResults.get(0).required("success").asBoolean());
        assertTrue(reportedResults.get(0).required("result").required("written").asBoolean());
        assertFalse(session.approve("call-1"));
    }

    @Test
    void denialSkipsToolAndReportsFailure() throws Exception {
        AgentSession session = newSession();

        assertTrue(toolEvent.await(2, TimeUnit.SECONDS));
        awaitDecision(session, false);

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(0, executions.get());
        assertEquals(1, reportedResults.size());
        assertFalse(reportedResults.get(0).required("success").asBoolean());
        assertEquals("not allowed", reportedResults.get(0).required("error_message").asText());
        assertTrue(reportedResults.get(0).required("result").isNull());
    }

    private AgentSession newSession() {
        AgentSession session = agent.newSession("session-1");
        session.onStream(new StreamHook() {
            @Override
            public void onEvent(String sessionId, io.taiyiflow.sdk.transport.envelope.StreamEvent event) {
                if (event.isClientTool()) {
                    toolEvent.countDown();
                }
            }
        });
        session.chat("write result");
        return session;
    }

    private static void awaitDecision(AgentSession session, boolean approve) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            boolean completed = approve ? session.approve("call-1") : session.deny("call-1", "not allowed");
            if (completed) {
                return;
            }
            Thread.sleep(5);
        }
        fail("Approval was not registered");
    }
}
