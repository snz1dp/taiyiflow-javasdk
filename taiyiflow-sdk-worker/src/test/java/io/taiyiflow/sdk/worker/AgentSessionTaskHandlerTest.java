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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionTaskHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
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

    @Test
    void pauseAndResumeUseRealSessionControls() throws Exception {
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch streamRelease = new CountDownLatch(1);
        AtomicInteger turn = new AtomicInteger();
        AtomicInteger pauseCalls = new AtomicInteger();
        server.createContext("/api/v1/run/flow-1/stream", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            if (turn.incrementAndGet() == 1) {
                holdFirstStream(exchange, firstRequest, streamRelease);
            } else {
                respond(exchange, 200, "event: message\ndata: {\"chunk\":\"resumed\",\"run_id\":\"run-1\"}\n\n"
                        + "event: close\ndata: {\"run_finished\":true}\n\n");
            }
        });
        server.createContext("/api/v1/run/sessions/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            pauseCalls.incrementAndGet();
            respond(exchange, 202, "{\"session_id\":\"client-session\",\"run_id\":\"run-1\","
                    + "\"status\":\"paused\",\"checkpoint_id\":\"checkpoint-1\"}");
        });
        server.createContext("/api/v1/run/checkpoints/resumable", exchange ->
                respond(exchange, 200, "[{\"id\":\"checkpoint-1\",\"session_id\":\"client-session\","
                        + "\"run_id\":\"run-1\",\"status\":\"ready\",\"version\":3}]"));
        server.start();
        AtomicReference<TaskControl> control = new AtomicReference<>();
        ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor();
        try (AgentSessionTaskHandler handler = new AgentSessionTaskHandler(
                flowId -> agent(flowId, () -> "token"))) {
            handler.onControl((task, value) -> control.set(value), (task, value) -> value.invalidate());
            Future<Object> result = caller.submit(() -> handler.execute(task("run"), progress -> { }));
            assertTrue(firstRequest.await(2, TimeUnit.SECONDS));

            control.get().pause();
            streamRelease.countDown();
            control.get().resume();

            ObjectNode completed = (ObjectNode) result.get(5, TimeUnit.SECONDS);
            assertEquals("workingresumed", completed.required("text").asText());
            assertEquals(1, pauseCalls.get());
            assertEquals(2, requests.size());
            assertEquals("resume", requests.get(1).required("execution_mode").asText());
            assertEquals("checkpoint-1", requests.get(1).required("checkpoint_id").asText());
            control.get().resume();
            assertEquals(2, requests.size());
        } finally {
            streamRelease.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void cancelTerminatesRunAndInvalidatesStaleControl() throws Exception {
        CountDownLatch firstRequest = new CountDownLatch(1);
        CountDownLatch streamRelease = new CountDownLatch(1);
        AtomicInteger cancelCalls = new AtomicInteger();
        server.createContext("/api/v1/run/flow-1/stream", exchange -> {
            mapper.readTree(exchange.getRequestBody());
            holdFirstStream(exchange, firstRequest, streamRelease);
        });
        server.createContext("/api/v1/run/sessions/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            cancelCalls.incrementAndGet();
            respond(exchange, 202, "{\"session_id\":\"client-session\",\"run_id\":\"run-1\","
                    + "\"status\":\"cancel_requested\"}");
        });
        server.start();
        AtomicReference<TaskControl> control = new AtomicReference<>();
        ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor();
        try (AgentSessionTaskHandler handler = new AgentSessionTaskHandler(
                flowId -> agent(flowId, () -> "token"))) {
            handler.onControl((task, value) -> control.set(value), (task, value) -> value.invalidate());
            Future<Object> result = caller.submit(() -> handler.execute(task("run"), progress -> { }));
            assertTrue(firstRequest.await(2, TimeUnit.SECONDS));

            control.get().cancel();
            streamRelease.countDown();

            ExecutionException error = assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof TaskCancelledException);
            assertEquals(1, cancelCalls.get());
            control.get().cancel();
            assertEquals(1, cancelCalls.get());
        } finally {
            streamRelease.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void dialogueSubtaskUsesFrozenCapabilitiesAndFiltersMcpResolver() throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        server.createContext("/api/v1/run/flow-1/stream", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, "event: message\ndata: {\"chunk\":\"child-result\",\"run_id\":\"child-run\"}\n\n"
                    + "event: close\ndata: {\"run_finished\":true}\n\n");
        });
        server.start();
        try (AgentSessionTaskHandler handler = new AgentSessionTaskHandler(
                flowId -> agent(flowId, () -> "token"))) {
            handler.subtaskMcpResolver((flowId, names) -> {
                ObjectNode resolved = mapper.createObjectNode();
                resolved.putObject("safe").put("url", "https://safe.example").put("token", "memory-only");
                resolved.putObject("not-allowed").put("token", "must-not-leak");
                return resolved;
            });
            ObjectNode result = (ObjectNode) handler.execute(subtask("plan"), progress -> { });

            assertEquals("child-result", result.path("text").asText());
            assertEquals("child-session", request.get().path("session_id").asText());
            assertEquals("child-run", request.get().path("run_id").asText());
            assertEquals("parent-run", request.get().path("parent_id").asText());
            assertEquals("plan", request.get().path("request_type").asText());
            assertEquals("search", request.get().path("available_tools").get(0).asText());
            assertEquals("review", request.get().path("requested_skill_names").get(0).asText());
            assertTrue(request.get().path("client_mcp_servers").has("safe"));
            assertFalse(request.get().path("client_mcp_servers").has("not-allowed"));
            assertFalse(request.get().toString().contains("must-not-leak"));
        }
    }

    @Test
    void dialogueSubtaskRejectsInvalidModeBeforeStartingRun() throws Exception {
        AtomicInteger streams = new AtomicInteger();
        server.createContext("/api/v1/run/flow-1/stream", exchange -> {
            streams.incrementAndGet();
            respond(exchange, 500, "unexpected");
        });
        server.start();
        try (AgentSessionTaskHandler handler = new AgentSessionTaskHandler(
                flowId -> agent(flowId, () -> "token"))) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> handler.execute(subtask("invalid"), progress -> { }));
            assertTrue(error.getMessage().contains("request_mode"));
            assertEquals(0, streams.get());
        }
    }

    private CollaborationPlugin.TaskDispatch subtask(String mode) {
        ObjectNode payload = mapper.createObjectNode()
                .put("task_id", "child-task")
                .put("task_kind", "dialogue_subtask")
                .put("version", 1)
                .put("child_session_id", "child-session")
                .put("child_run_id", "child-run")
                .put("source_session_id", "parent-session")
                .put("source_run_id", "parent-run");
        ObjectNode command = payload.putObject("command_payload")
                .put("flow_id", "flow-1")
                .put("input_value", "child-input")
                .put("request_mode", mode);
        command.putArray("available_tools").add("search");
        ObjectNode features = command.putObject("features");
        features.putArray("requested_skill_names").add("review");
        features.putArray("client_mcp_server_names").add("safe");
        return CollaborationPlugin.taskFromPayload(payload, "remote_task");
    }

    private void holdFirstStream(HttpExchange exchange, CountDownLatch firstRequest,
                                 CountDownLatch streamRelease) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(("event: message\ndata: {\"chunk\":\"working\","
                + "\"run_id\":\"run-1\",\"execution_attempt_id\":\"attempt-1\",\"event_id\":\"event-1\"}\n\n")
                .getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
        firstRequest.countDown();
        try {
            streamRelease.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
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
