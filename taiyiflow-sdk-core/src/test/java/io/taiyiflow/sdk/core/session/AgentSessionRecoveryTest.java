package io.taiyiflow.sdk.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.RecoveryPolicy;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.state.SessionState;
import io.taiyiflow.sdk.transport.envelope.ResumableCheckpoint;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentSessionRecoveryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Queue<StreamBehavior> streamBehaviors = new ConcurrentLinkedQueue<>();
    private final List<JsonNode> streamRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> toolResultRequests = new CopyOnWriteArrayList<>();
    private final CountDownLatch initialRelease = new CountDownLatch(1);
    private final AtomicInteger checkpointCalls = new AtomicInteger();
    private final AtomicInteger toolResultCalls = new AtomicInteger();

    private HttpServer server;
    private ExecutorService serverExecutor;
    private TaiyiAgent agent;
    private volatile String checkpointResponse = "[]";
    private volatile String sessionResponse = "{}";
    private volatile int sessionStatus = 200;
    private volatile int toolResultStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/api/v1/run/flow-1/stream", this::handleStream);
        server.createContext("/api/v1/run/sessions/", this::handleControl);
        server.createContext("/api/v1/run/checkpoints/resumable", exchange -> {
            checkpointCalls.incrementAndGet();
            respond(exchange, 200, checkpointResponse);
        });
        server.createContext("/api/v1/agents/flows/flow-1/sessions/", exchange ->
                respond(exchange, sessionStatus, sessionResponse));
        server.createContext("/api/v1/agents/client/call/result", exchange -> {
            toolResultRequests.add(mapper.readTree(exchange.getRequestBody()));
            toolResultCalls.incrementAndGet();
            respond(exchange, toolResultStatus, "{}");
        });
        server.start();
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
    void pauseThenDiscoversCheckpointAndResumes() throws Exception {
        streamBehaviors.add(this::initialRunAndHold);
        streamBehaviors.add((exchange, request) -> complete(exchange));
        agent = newAgent(RecoveryPolicy.MANUAL, 1);
        AgentSession session = agent.newSession("session-1").chat("hello");
        awaitRun(session);
        session.pause();
        assertEquals(SessionState.PAUSED, session.getState());
        checkpointResponse = """
                [{"id":"checkpoint-1","session_id":"session-1","run_id":"run-1",
                  "status":"ready","version":3}]
                """;

        session.resume();

        assertTrue(session.await(Duration.ofSeconds(5)), () -> "state=" + session.getState()
                + ", requests=" + streamRequests.size()
                + ", generation=" + session.getRunContext().getRecoveryGeneration());
        assertEquals(SessionState.DONE, session.getState());
        assertEquals(2, streamRequests.size());
        JsonNode resume = streamRequests.get(1);
        assertEquals("resume", resume.required("execution_mode").asText());
        assertEquals("", resume.required("input_value").asText());
        assertEquals("run-1", resume.required("run_id").asText());
        assertEquals("checkpoint-1", resume.required("checkpoint_id").asText());
        assertEquals(3, resume.required("checkpoint_version").asInt());
        assertIdentity(resume);
        assertFalse(resume.required("subscription_id").asText().isBlank());
    }

    @Test
    void attachUsesCurrentExecutionCursorAndIdentity() throws Exception {
        streamBehaviors.add(this::initialRunAndHold);
        streamBehaviors.add((exchange, request) -> complete(exchange));
        agent = newAgent(RecoveryPolicy.MANUAL, 1);
        AgentSession session = agent.newSession("session-1").chat("hello");
        awaitRun(session);
        session.pause();

        session.attach();

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.DONE, session.getState());
        JsonNode attach = streamRequests.get(1);
        assertEquals("attach", attach.required("execution_mode").asText());
        assertEquals("run-1", attach.required("run_id").asText());
        assertEquals("attempt-1", attach.required("execution_attempt_id").asText());
        assertEquals("event-1", attach.required("after_event_id").asText());
        assertIdentity(attach);
        assertFalse(attach.required("subscription_id").asText().isBlank());
    }

    @Test
    void cancelledSessionCannotBeResumedOrAttached() throws Exception {
        streamBehaviors.add(this::initialRunAndHold);
        agent = newAgent(RecoveryPolicy.MANUAL, 1);
        AgentSession session = agent.newSession("session-1").chat("hello");
        awaitRun(session);
        session.cancel();

        assertEquals(SessionState.CANCELLED, session.getState());
        assertThrows(IllegalStateException.class, session::resume);
        assertThrows(IllegalStateException.class, session::attach);
    }

    @Test
    void autoReconnectAttachesToActiveRun() throws Exception {
        streamBehaviors.add(this::initialRunAndEof);
        streamBehaviors.add((exchange, request) -> complete(exchange));
        sessionResponse = """
                {"run_status":"running","executing":true,"executing_run_id":"run-1",
                 "execution_attempt_id":"attempt-2"}
                """;
        agent = newAgent(RecoveryPolicy.AUTO, 2);

        AgentSession session = agent.newSession("session-1").chat("hello");

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.DONE, session.getState());
        assertEquals(2, streamRequests.size());
        JsonNode attach = streamRequests.get(1);
        assertEquals("attach", attach.required("execution_mode").asText());
        assertEquals("attempt-2", attach.required("execution_attempt_id").asText());
        assertEquals("event-1", attach.required("after_event_id").asText());
    }

    @Test
    void autoReconnectPrefersUsableCheckpointOverAttach() throws Exception {
        streamBehaviors.add(this::initialRunAndEof);
        streamBehaviors.add((exchange, request) -> complete(exchange));
        checkpointResponse = """
                [{"id":"checkpoint-2","session_id":"session-1","run_id":"run-1",
                  "status":"waiting","version":4,"waiting_call_ids":["call-1"]}]
                """;
        sessionResponse = """
                {"run_status":"running","executing":true,"executing_run_id":"run-1"}
                """;
        agent = newAgent(RecoveryPolicy.AUTO, 2);

        AgentSession session = agent.newSession("session-1").chat("hello");

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals("resume", streamRequests.get(1).required("execution_mode").asText());
        assertEquals("checkpoint-2", streamRequests.get(1).required("checkpoint_id").asText());
        assertFalse(streamRequests.get(1).has("tool_results"));
    }

    @Test
    void autoReconnectStopsForRequiredUserResume() throws Exception {
        streamBehaviors.add(this::initialRunAndEof);
        checkpointResponse = """
                [{"id":"checkpoint-1","session_id":"session-1","run_id":"run-1",
                  "status":"paused","version":2,"requires_user_resume":true}]
                """;
        agent = newAgent(RecoveryPolicy.AUTO, 2);

        AgentSession session = agent.newSession("session-1").chat("hello");

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.PAUSED, session.getState());
        assertEquals(1, streamRequests.size());
    }

    @Test
    void confirmedExecutionLossStartsNewRunWithOriginalInput() throws Exception {
        streamBehaviors.add(this::initialRunAndEof);
        streamBehaviors.add((exchange, request) -> complete(exchange));
        sessionResponse = "{\"run_status\":\"failed\",\"executing\":false}";
        agent = newAgent(RecoveryPolicy.AUTO, 2);

        AgentSession session = agent.newSession("session-1").chat("hello");

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.DONE, session.getState());
        JsonNode restart = streamRequests.get(1);
        assertEquals("start", restart.required("execution_mode").asText());
        assertEquals("hello", restart.required("input_value").asText());
        assertEquals("session-1", restart.required("session_id").asText());
        assertNotEquals("run-1", restart.required("run_id").asText());
        assertNotEquals(streamRequests.get(0).required("message_id").asText(),
                restart.required("message_id").asText());
        assertNotEquals(streamRequests.get(0).required("subscription_id").asText(),
                restart.required("subscription_id").asText());
    }

    @Test
    void unknownExecutionStateNeverRestarts() throws Exception {
        streamBehaviors.add(this::initialRunAndEof);
        sessionResponse = "{\"run_status\":\"mystery\",\"executing\":false}";
        agent = newAgent(RecoveryPolicy.AUTO, 2);

        AgentSession session = agent.newSession("session-1").chat("hello");

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.ERROR, session.getState());
        assertEquals(1, streamRequests.size());
        assertEquals(2, checkpointCalls.get());
    }

    @Test
    void unresolvedToolCallsPreventAutomaticRestart() throws Exception {
        streamBehaviors.add(this::initialRunAndEof);
        checkpointResponse = """
                [{"id":"checkpoint-1","session_id":"session-1","run_id":"run-1",
                  "status":"expired","version":2,"waiting_call_ids":["call-1"],
                  "pending_call_ids":["call-2"],"uncertain_call_ids":["call-3"]}]
                """;
        sessionResponse = "{\"run_status\":\"failed\",\"executing\":false}";
        agent = newAgent(RecoveryPolicy.AUTO, 1);

        AgentSession session = agent.newSession("session-1").chat("hello");

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.ERROR, session.getState());
        assertEquals(1, streamRequests.size());
    }

    @Test
    void unacknowledgedToolResultIsSubmittedWithResume() throws Exception {
        streamBehaviors.add(this::toolCallAndHold);
        streamBehaviors.add((exchange, request) -> complete(exchange));
        toolResultStatus = 500;
        agent = newAgent(RecoveryPolicy.MANUAL, 1);
        agent.getPluginManager().register("get_weather", call ->
                ToolResult.success(call.getId(), mapper.createObjectNode().put("temperature", 22)));
        AgentSession session = agent.newSession("session-1").chat("weather");
        awaitRun(session);
        awaitValue(() -> toolResultCalls.get() == 4);
        session.pause();

        ResumableCheckpoint checkpoint = new ResumableCheckpoint();
        checkpoint.setId("checkpoint-1");
        checkpoint.setSessionId("session-1");
        checkpoint.setRunId("run-1");
        checkpoint.setVersion(5);
        checkpoint.setStatus("waiting");
        checkpoint.setWaitingCallIds(List.of("call-1"));
        session.resume(checkpoint);

        assertTrue(session.await(Duration.ofSeconds(5)));
        JsonNode resume = streamRequests.get(1);
        assertEquals("resume", resume.required("execution_mode").asText());
        assertEquals(1, resume.required("tool_results").size());
        JsonNode result = resume.required("tool_results").get(0);
        assertEquals("call-1", result.required("call_id").asText());
        assertTrue(result.required("success").asBoolean());
        assertEquals(22, result.required("result").required("temperature").asInt());
        assertFalse(resume.required("submission_id").asText().isBlank());
    }

    @Test
    void freshSessionAdoptsCheckpointIdentityForPlatformDrivenResume() throws Exception {
        streamBehaviors.add((exchange, request) -> complete(exchange));
        agent = newAgent(RecoveryPolicy.MANUAL, 1);
        AgentSession session = agent.newSession("session-1");

        ResumableCheckpoint checkpoint = new ResumableCheckpoint();
        checkpoint.setId("checkpoint-1");
        checkpoint.setSessionId("session-1");
        checkpoint.setRunId("run-1");
        checkpoint.setVersion(5);
        checkpoint.setStatus("ready");
        session.resume(checkpoint);

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.DONE, session.getState());
        assertEquals(1, streamRequests.size());
        JsonNode resume = streamRequests.get(0);
        assertEquals("resume", resume.required("execution_mode").asText());
        assertEquals("run-1", resume.required("run_id").asText());
        assertEquals("checkpoint-1", resume.required("checkpoint_id").asText());
        assertEquals(5, resume.required("checkpoint_version").asInt());
        assertEquals(0, checkpointCalls.get());
    }

    @Test
    void unacknowledgedToolResultIsReplayedBeforeAttach() throws Exception {
        streamBehaviors.add(this::toolCallAndHold);
        streamBehaviors.add((exchange, request) -> complete(exchange));
        toolResultStatus = 500;
        agent = newAgent(RecoveryPolicy.MANUAL, 1);
        agent.getPluginManager().register("get_weather", call ->
                ToolResult.success(call.getId(), mapper.createObjectNode().put("temperature", 22)));
        AgentSession session = agent.newSession("session-1").chat("weather");
        awaitRun(session);
        awaitValue(() -> toolResultCalls.get() == 4);
        session.pause();
        toolResultStatus = 200;

        session.attach();

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.DONE, session.getState());
        assertEquals(5, toolResultCalls.get());
        assertEquals(2, streamRequests.size());
        assertEquals("attach", streamRequests.get(1).required("execution_mode").asText());
        assertFalse(streamRequests.get(1).has("tool_results"));
        assertEquals(toolResultRequests.get(0), toolResultRequests.get(4));
        assertEquals("call-1", toolResultRequests.get(4).required("call_id").asText());
        assertEquals(22, toolResultRequests.get(4).required("result").required("temperature").asInt());
    }

    @Test
    void autoReconnectBeforeFirstEventAttachesToServerRun() throws Exception {
        streamBehaviors.add((exchange, request) -> {
            openSse(exchange);
            exchange.close();
        });
        streamBehaviors.add((exchange, request) -> complete(exchange));
        sessionResponse = """
                {"run_status":"running","executing":true,"executing_run_id":"run-1",
                 "execution_attempt_id":"attempt-1"}
                """;
        agent = newAgent(RecoveryPolicy.AUTO, 1);

        AgentSession session = agent.newSession("session-1").chat("hello");

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.DONE, session.getState());
        assertEquals("attach", streamRequests.get(1).required("execution_mode").asText());
        assertEquals("run-1", streamRequests.get(1).required("run_id").asText());
    }

    @Test
    void observedToolCallPreventsAutomaticRestartWithoutCheckpoint() throws Exception {
        streamBehaviors.add(this::toolCallAndEof);
        sessionResponse = "{\"run_status\":\"failed\",\"executing\":false}";
        agent = newAgent(RecoveryPolicy.AUTO, 1);
        agent.getPluginManager().register("get_weather", call ->
                ToolResult.success(call.getId(), mapper.createObjectNode().put("temperature", 22)));

        AgentSession session = agent.newSession("session-1").chat("weather");

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.ERROR, session.getState());
        assertEquals(1, streamRequests.size());
        assertEquals(1, toolResultCalls.get());
    }

    @Test
    void pauseAbortsInFlightToolDiscardsLateResultAndResumeRestoresExecution() throws Exception {
        streamBehaviors.add(this::toolCallAndHold);
        streamBehaviors.add((exchange, request) -> {
            openSse(exchange);
            sendEvent(exchange, "client_tool", """
                    {"function":{"id":"call-1","name":"get_weather","arguments":{"city":"Beijing"}},
                     "context":{"session_id":"session-1","run_id":"run-1","message_id":"message-1"}}
                    """);
            sendEvent(exchange, "close", "{\"run_finished\":true}");
            exchange.close();
        });
        agent = newAgent(RecoveryPolicy.MANUAL, 1);
        AtomicInteger invocations = new AtomicInteger();
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch toolRelease = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        agent.getPluginManager().register("get_weather", call -> {
            if (invocations.incrementAndGet() == 1) {
                toolStarted.countDown();
                while (toolRelease.getCount() > 0) {
                    try {
                        toolRelease.await();
                    } catch (InterruptedException e) {
                        interrupted.set(true);
                    }
                }
            }
            return ToolResult.success(call.getId(), mapper.createObjectNode().put("temperature", 22));
        });
        AgentSession session = agent.newSession("session-1").chat("weather");
        awaitRun(session);
        assertTrue(toolStarted.await(5, TimeUnit.SECONDS));

        session.pause();
        assertEquals(SessionState.PAUSED, session.getState());
        awaitValue(interrupted::get);
        toolRelease.countDown();
        assertEquals(0, toolResultCalls.get(), "late result after pause must not be reported");

        checkpointResponse = """
                [{"id":"checkpoint-1","session_id":"session-1","run_id":"run-1",
                  "status":"ready","version":3}]
                """;
        session.resume();

        assertTrue(session.await(Duration.ofSeconds(5)), () -> "state=" + session.getState());
        assertEquals(SessionState.DONE, session.getState());
        assertEquals(2, invocations.get());
        assertEquals(1, toolResultCalls.get(), "resume must restore tool execution with fresh cancellation");
        assertEquals("call-1", toolResultRequests.get(0).required("call_id").asText());
    }

    @Test
    void cancelAbortsInFlightToolAndNeverReportsItsResult() throws Exception {
        streamBehaviors.add(this::toolCallAndHold);
        agent = newAgent(RecoveryPolicy.MANUAL, 1);
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch toolRelease = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch toolFinished = new CountDownLatch(1);
        agent.getPluginManager().register("get_weather", call -> {
            toolStarted.countDown();
            while (toolRelease.getCount() > 0) {
                try {
                    toolRelease.await();
                } catch (InterruptedException e) {
                    interrupted.set(true);
                }
            }
            toolFinished.countDown();
            return ToolResult.success(call.getId(), mapper.createObjectNode().put("temperature", 22));
        });
        AgentSession session = agent.newSession("session-1").chat("weather");
        awaitRun(session);
        assertTrue(toolStarted.await(5, TimeUnit.SECONDS));

        session.cancel();
        assertEquals(SessionState.CANCELLED, session.getState());
        awaitValue(interrupted::get);
        toolRelease.countDown();
        assertTrue(toolFinished.await(5, TimeUnit.SECONDS));
        assertEquals(0, toolResultCalls.get(), "late result after cancel must not be reported");
    }

    @Test
    void resumeStreamWithoutFirstEventTimesOutAndRetries() throws Exception {
        streamBehaviors.add(this::initialRunAndEof);
        streamBehaviors.add((exchange, request) -> {
            openSse(exchange);
            awaitInitialRelease();
            exchange.close();
        });
        streamBehaviors.add((exchange, request) -> complete(exchange));
        checkpointResponse = """
                [{"id":"checkpoint-2","session_id":"session-1","run_id":"run-1",
                  "status":"ready","version":4}]
                """;
        agent = newAgent(RecoveryPolicy.AUTO, 3);

        AgentSession session = agent.newSession("session-1").chat("hello");
        ((AgentSessionImpl) session).resumeEstablishTimeout(Duration.ofMillis(200));

        assertTrue(session.await(Duration.ofSeconds(5)), () -> "state=" + session.getState()
                + ", requests=" + streamRequests.size());
        assertEquals(SessionState.DONE, session.getState());
        assertEquals(3, streamRequests.size());
        assertEquals("resume", streamRequests.get(1).required("execution_mode").asText());
        assertEquals("resume", streamRequests.get(2).required("execution_mode").asText());
        assertEquals("checkpoint-2", streamRequests.get(2).required("checkpoint_id").asText());
    }

    @Test
    void checkpointResumeIsBlockedWhileLeaseHeldElsewhere() throws Exception {
        streamBehaviors.add(this::initialRunAndHold);
        streamBehaviors.add((exchange, request) -> complete(exchange));
        agent = newAgent(RecoveryPolicy.MANUAL, 1);
        AgentSession session = agent.newSession("session-1").chat("hello");
        awaitRun(session);
        session.pause();
        ResumableCheckpoint checkpoint = mapper.readValue("""
                {"id":"checkpoint-1","session_id":"session-1","run_id":"run-1",
                 "status":"ready","version":3}
                """, ResumableCheckpoint.class);
        ResumeLeaseRegistry leases = agent.getSessionManager().getResumeLeases();
        assertTrue(leases.tryAcquire("session-1/checkpoint-1", "other-owner"));

        IllegalStateException blocked = assertThrows(IllegalStateException.class,
                () -> session.resume(checkpoint));
        assertTrue(blocked.getMessage().contains("Resume lease"));
        assertEquals(1, streamRequests.size());

        leases.release("session-1/checkpoint-1", "other-owner");
        session.resume(checkpoint);

        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.DONE, session.getState());
        assertEquals("resume", streamRequests.get(1).required("execution_mode").asText());
        assertEquals("checkpoint-1", streamRequests.get(1).required("checkpoint_id").asText());
        assertEquals(3, streamRequests.get(1).required("checkpoint_version").asInt());
    }

    @Test
    void resumeLeaseIsHeldWhileResumedRunStreamsAndReleasedOnCompletion() throws Exception {
        CountDownLatch resumeRelease = new CountDownLatch(1);
        CountDownLatch resumeEventSeen = new CountDownLatch(1);
        streamBehaviors.add(this::initialRunAndHold);
        streamBehaviors.add((exchange, request) -> {
            openSse(exchange);
            sendEvent(exchange, "message", """
                    {"chunk":"resumed","run_id":"run-1","event_id":"event-2"}
                    """);
            resumeEventSeen.countDown();
            try {
                assertTrue(resumeRelease.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sendEvent(exchange, "close", "{\"run_finished\":true}");
            exchange.close();
        });
        agent = newAgent(RecoveryPolicy.MANUAL, 1);
        AgentSession session = agent.newSession("session-1").chat("hello");
        awaitRun(session);
        session.pause();
        ResumableCheckpoint checkpoint = mapper.readValue("""
                {"id":"checkpoint-1","session_id":"session-1","run_id":"run-1",
                 "status":"ready","version":3}
                """, ResumableCheckpoint.class);
        ResumeLeaseRegistry leases = agent.getSessionManager().getResumeLeases();

        session.resume(checkpoint);
        assertTrue(resumeEventSeen.await(5, TimeUnit.SECONDS));
        awaitValue(() -> session.getState() == SessionState.STREAMING);
        // Kickoff-only leasing would let a concurrent resume of the same checkpoint through here.
        assertFalse(leases.tryAcquire("session-1/checkpoint-1", "other-owner"));

        resumeRelease.countDown();
        assertTrue(session.await(Duration.ofSeconds(5)));
        assertEquals(SessionState.DONE, session.getState());
        assertTrue(leases.tryAcquire("session-1/checkpoint-1", "other-owner"));
        leases.release("session-1/checkpoint-1", "other-owner");
    }

    private TaiyiAgent newAgent(RecoveryPolicy policy, int attempts) {
        return TaiyiAgent.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key")
                .flowId("flow-1")
                .clientId("client-1")
                .deviceId("device-1")
                .streamProtocol(StreamProtocol.SSE)
                .recoveryPolicy(policy)
                .recoveryBackoff(Duration.ofMillis(1), Duration.ofMillis(2))
                .recoveryMaxAttempts(attempts)
                .build();
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        try {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            streamRequests.add(request);
            StreamBehavior behavior = streamBehaviors.poll();
            if (behavior == null) {
                respond(exchange, 500, "{}");
                return;
            }
            behavior.handle(exchange, request);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            respond(exchange, 500, "{}");
        }
    }

    private void handleControl(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        initialRelease.countDown();
        if (exchange.getRequestURI().getPath().endsWith("/pause")) {
            respond(exchange, 202, """
                    {"session_id":"session-1","run_id":"run-1","status":"paused",
                     "request_id":"request-1","checkpoint_id":"checkpoint-1"}
                    """);
        } else {
            respond(exchange, 202, """
                    {"session_id":"session-1","run_id":"run-1","status":"cancel_requested",
                     "request_id":"request-2"}
                    """);
        }
    }

    private void initialRunAndHold(HttpExchange exchange, JsonNode request) throws Exception {
        openSse(exchange);
        sendEvent(exchange, "message", """
                {"chunk":"working","run_id":"run-1","execution_attempt_id":"attempt-1",
                 "event_id":"event-1"}
                """);
        awaitInitialRelease();
        exchange.close();
    }

    private void initialRunAndEof(HttpExchange exchange, JsonNode request) throws Exception {
        openSse(exchange);
        sendEvent(exchange, "message", """
                {"chunk":"working","run_id":"run-1","execution_attempt_id":"attempt-1",
                 "event_id":"event-1"}
                """);
        exchange.close();
    }

    private void toolCallAndEof(HttpExchange exchange, JsonNode request) throws Exception {
        openSse(exchange);
        sendEvent(exchange, "client_tool", """
                {"function":{"id":"call-1","name":"get_weather","arguments":{"city":"Beijing"}},
                 "context":{"session_id":"session-1","run_id":"run-1","message_id":"message-1"}}
                """);
        exchange.close();
    }

    private void toolCallAndHold(HttpExchange exchange, JsonNode request) throws Exception {
        openSse(exchange);
        sendEvent(exchange, "message", """
                {"chunk":"working","run_id":"run-1","execution_attempt_id":"attempt-1",
                 "event_id":"event-1"}
                """);
        sendEvent(exchange, "client_tool", """
                {"function":{"id":"call-1","name":"get_weather","arguments":{"city":"Beijing"}},
                 "context":{"session_id":"session-1","run_id":"run-1","message_id":"message-1"}}
                """);
        awaitInitialRelease();
        exchange.close();
    }

    private static void complete(HttpExchange exchange) throws IOException {
        openSse(exchange);
        sendEvent(exchange, "close", "{\"run_finished\":true}");
        exchange.close();
    }

    private static void openSse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
    }

    private static void sendEvent(HttpExchange exchange, String type, String data) throws IOException {
        String compact = data.lines().map(String::trim).reduce("", (left, right) -> left + right);
        exchange.getResponseBody().write(("event: " + type + "\ndata: " + compact + "\n\n")
                .getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }

    private void awaitInitialRelease() throws InterruptedException {
        if (!initialRelease.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Initial stream was not released");
        }
    }

    private static void awaitRun(AgentSession session) throws InterruptedException {
        awaitValue(() -> "run-1".equals(session.getRunContext().getRunId())
                && "event-1".equals(session.getRunContext().getAfterEventId()));
    }

    private static void awaitValue(Check check) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(check.getAsBoolean());
    }

    private static void assertIdentity(JsonNode request) {
        assertEquals("client-1", request.required("client_id").asText());
        assertEquals("device-1", request.required("device_id").asText());
        assertEquals("device", request.required("client_type").asText());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface StreamBehavior {
        void handle(HttpExchange exchange, JsonNode request) throws Exception;
    }

    @FunctionalInterface
    private interface Check {
        boolean getAsBoolean();
    }
}
