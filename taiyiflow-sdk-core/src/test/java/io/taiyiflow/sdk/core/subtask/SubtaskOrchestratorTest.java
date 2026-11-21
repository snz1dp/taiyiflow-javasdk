package io.taiyiflow.sdk.core.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import io.taiyiflow.sdk.transport.rest.RestClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SubtaskOrchestratorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "/api/v1/agents/client/collaborations";

    @Test
    void rejectsEscalationAndInvalidModeBeforeCreatingTasks() {
        FakeRest rest = new FakeRest();
        SubtaskOrchestrator orchestrator = orchestrator(rest, null);
        ToolExecutionContext context = context("plan", SubtaskCapabilitySnapshot.empty(), null);

        ToolResult escalation = orchestrator.execute(context, call(MAPPER.createObjectNode()
                .put("prompt", "write").put("request_mode", "agent").put("delivery_policy", "remote")));
        assertFalse(escalation.isSuccess());
        assertTrue(escalation.getError().contains("cannot exceed"));

        ToolResult invalid = orchestrator.execute(context, call(MAPPER.createObjectNode()
                .put("prompt", "inspect").put("request_mode", "invalid").put("delivery_policy", "remote")));
        assertFalse(invalid.isSuccess());
        assertTrue(invalid.getError().contains("Invalid request_mode"));
        assertEquals(0, rest.createCount.get());
    }

    @Test
    void createsPlatformTasksAndAggregatesByInputIndex() {
        FakeRest rest = new FakeRest();
        SubtaskOrchestrator orchestrator = orchestrator(rest, null);
        SubtaskCapabilitySnapshot snapshot = new SubtaskCapabilitySnapshot(true, false, null, null,
                List.of("review"), List.of("safe-mcp"), Set.of("search", "run_subtask"));
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.putArray("subtasks").add("first").add("second");
        arguments.put("delivery_policy", "remote");
        arguments.put("max_parallelism", 2);

        ToolResult result = orchestrator.execute(context("agent", snapshot, null), call(arguments));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals(2, rest.createCount.get());
        ArrayNode results = (ArrayNode) result.getResult().path("results");
        assertEquals(0, results.get(0).path("index").asInt());
        assertEquals(1, results.get(1).path("index").asInt());
        assertEquals("success", results.get(0).path("status").asText());
        assertEquals("success", results.get(1).path("status").asText());
        ObjectNode payload = rest.created.get(0);
        assertEquals("dialogue_subtask", payload.path("task_kind").asText());
        assertEquals("agent", payload.path("command_payload").path("request_mode").asText());
        assertEquals("parent-run", payload.path("source_run_id").asText());
        assertEquals("parent-run", payload.path("command_payload").path("parent_id").asText());
        assertEquals("review", payload.path("command_payload").path("features")
                .path("requested_skill_names").get(0).asText());
        assertEquals("safe-mcp", payload.path("command_payload").path("features")
                .path("client_mcp_server_names").get(0).asText());
        assertFalse(payload.toString().contains("secret"));
    }

    @Test
    void reconcilesLostCreateResponseWithoutRepeatingPost() {
        FakeRest rest = new FakeRest();
        rest.failCreateAfterPersist = true;
        SubtaskOrchestrator orchestrator = orchestrator(rest, null);

        ToolResult result = orchestrator.execute(context("agent", SubtaskCapabilitySnapshot.empty(), null),
                call(MAPPER.createObjectNode().put("prompt", "recover").put("delivery_policy", "remote")));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals(1, rest.createCount.get());
        assertEquals(1, rest.listCount.get());
        assertEquals("task-1", result.getResult().path("results").get(0).path("task_id").asText());
        assertEquals("success", result.getResult().path("results").get(0).path("status").asText());
    }

    @Test
    void timeoutCancelsWithRequestIdAndExpectedVersion() {
        FakeRest rest = new FakeRest();
        rest.terminalImmediately = false;
        SubtaskOrchestrator orchestrator = new SubtaskOrchestrator(rest, "client", "device", null,
                4, 2, Duration.ofSeconds(5), Duration.ofMillis(5));

        ToolResult result = orchestrator.execute(context("agent", SubtaskCapabilitySnapshot.empty(), null),
                call(MAPPER.createObjectNode().put("prompt", "wait").put("delivery_policy", "remote")
                        .put("timeout_seconds", 1)));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals("timeout", result.getResult().path("results").get(0).path("status").asText());
        assertEquals(1, rest.cancellations.size());
        assertEquals(1, rest.cancellations.get(0).path("expected_version").asLong());
        assertFalse(rest.cancellations.get(0).path("request_id").asText().isBlank());
    }

    @Test
    void parentCancellationDoesNotCreateQueuedTasks() throws Exception {
        FakeRest rest = new FakeRest();
        rest.terminalImmediately = false;
        SubtaskOrchestrator orchestrator = new SubtaskOrchestrator(rest, "client", "device", null,
                4, 1, Duration.ofSeconds(30), Duration.ofMillis(5));
        ToolExecutionContext.CancellationSource cancellation = new ToolExecutionContext.CancellationSource();
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.putArray("subtasks").add("running").add("queued");
        arguments.put("delivery_policy", "remote");
        arguments.put("timeout_seconds", 20);
        ToolExecutionContext context = context("agent", SubtaskCapabilitySnapshot.empty(), cancellation);

        CompletableFuture<ToolResult> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> future.complete(orchestrator.execute(context, call(arguments))));
        long deadline = System.currentTimeMillis() + 2000;
        while (rest.createCount.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertEquals(1, rest.createCount.get());
        cancellation.cancel();
        ToolResult result = future.get();

        assertTrue(result.isSuccess(), result.getError());
        assertEquals(1, rest.createCount.get());
        assertEquals("cancelled", result.getResult().path("results").get(0).path("status").asText());
        assertEquals("cancelled", result.getResult().path("results").get(1).path("status").asText());
        assertEquals(1, rest.cancellations.size());
    }

    @Test
    void cancelInterruptsPollingWithoutWaitingOutTheInterval() throws Exception {
        FakeRest rest = new FakeRest();
        rest.terminalImmediately = false;
        SubtaskOrchestrator orchestrator = new SubtaskOrchestrator(rest, "client", "device", null,
                4, 1, Duration.ofSeconds(60), Duration.ofSeconds(30));
        ToolExecutionContext.CancellationSource cancellation = new ToolExecutionContext.CancellationSource();
        ToolExecutionContext context = context("agent", SubtaskCapabilitySnapshot.empty(), cancellation);

        CompletableFuture<ToolResult> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> future.complete(orchestrator.execute(context,
                call(MAPPER.createObjectNode().put("prompt", "wait").put("delivery_policy", "remote")
                        .put("timeout_seconds", 60)))));
        long deadline = System.currentTimeMillis() + 2000;
        while (rest.getCount.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertTrue(rest.getCount.get() > 0, "polling should have started");
        long started = System.nanoTime();
        cancellation.cancel();
        ToolResult result = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMillis < 5000, "cancel must interrupt the poll sleep, took " + elapsedMillis + "ms");
        assertEquals("cancelled", result.getResult().path("results").get(0).path("status").asText());
        assertEquals(1, rest.cancellations.size());
        assertFalse(rest.cancellations.get(0).path("request_id").asText().isBlank());
    }

    @Test
    void cancelDuringCreateWindowCancelsPlatformTaskBeforeLocalStart() throws Exception {
        FakeRest rest = new FakeRest();
        rest.terminalImmediately = false;
        rest.createGate = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger localSubmits = new AtomicInteger();
        LocalSubtaskExecutor local = new LocalSubtaskExecutor() {
            @Override
            public boolean submit(SubtaskTask task, ToolExecutionContext.Cancellation cancellation) {
                localSubmits.incrementAndGet();
                return true;
            }
        };
        SubtaskOrchestrator orchestrator = new SubtaskOrchestrator(rest, "client", "device", local,
                4, 1, Duration.ofSeconds(30), Duration.ofMillis(5));
        ToolExecutionContext.CancellationSource cancellation = new ToolExecutionContext.CancellationSource();
        ToolExecutionContext context = context("agent", SubtaskCapabilitySnapshot.empty(), cancellation);

        CompletableFuture<ToolResult> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> future.complete(orchestrator.execute(context,
                call(MAPPER.createObjectNode().put("prompt", "local work").put("timeout_seconds", 20)))));
        long deadline = System.currentTimeMillis() + 2000;
        while (rest.createCount.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertEquals(1, rest.createCount.get());
        cancellation.cancel();
        rest.createGate.countDown();
        ToolResult result = future.get(3, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals("cancelled", result.getResult().path("results").get(0).path("status").asText());
        assertEquals("task-1", result.getResult().path("results").get(0).path("task_id").asText());
        assertEquals(0, localSubmits.get(), "cancelled subtask must never start locally");
        assertEquals(1, rest.cancellations.size());
        assertFalse(rest.cancellations.get(0).path("request_id").asText().isBlank());
    }

    @Test
    void remoteDetachedReturnsImmediatelyAfterCreateWithoutPolling() {
        FakeRest rest = new FakeRest();
        rest.terminalImmediately = false;
        SubtaskOrchestrator orchestrator = orchestrator(rest, null);

        ToolResult result = orchestrator.execute(context("agent", SubtaskCapabilitySnapshot.empty(), null),
                call(MAPPER.createObjectNode().put("prompt", "detach").put("delivery_policy", "remote")
                        .put("wait_for_completion", false)));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals(1, rest.createCount.get());
        assertEquals(0, rest.getCount.get());
        assertEquals(0, rest.listCount.get());
        ObjectNode item = (ObjectNode) result.getResult().path("results").get(0);
        assertEquals("task-1", item.path("task_id").asText());
        assertEquals("dispatched", item.path("status").asText());
        assertTrue(item.path("detached").asBoolean());
        assertEquals("dialogue_subtask", item.path("task_kind").asText());
        assertFalse(item.path("child_session_id").asText().isBlank());
        assertFalse(item.path("child_run_id").asText().isBlank());
        assertEquals("parent-session", item.path("source_session_id").asText());
        assertEquals("parent-run", item.path("source_run_id").asText());
        assertEquals("parent-message", item.path("source_message_id").asText());
        assertEquals(1, result.getResult().path("successful").asInt());
        assertFalse(rest.created.get(0).path("command_payload").path("wait_for_completion").asBoolean(true));
    }

    @Test
    void detachedReconcilesLostCreateResponseWithoutPolling() {
        FakeRest rest = new FakeRest();
        rest.failCreateAfterPersist = true;
        rest.terminalImmediately = false;
        SubtaskOrchestrator orchestrator = orchestrator(rest, null);

        ToolResult result = orchestrator.execute(context("agent", SubtaskCapabilitySnapshot.empty(), null),
                call(MAPPER.createObjectNode().put("prompt", "recover detached").put("delivery_policy", "remote")
                        .put("wait_for_completion", false)));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals(1, rest.createCount.get());
        assertEquals(1, rest.listCount.get());
        assertEquals(0, rest.getCount.get());
        ObjectNode item = (ObjectNode) result.getResult().path("results").get(0);
        assertEquals("task-1", item.path("task_id").asText());
        assertEquals("dispatched", item.path("status").asText());
        assertTrue(item.path("detached").asBoolean());
    }

    @Test
    void localSubtaskAlwaysWaitsEvenWhenDetachedRequested() {
        FakeRest rest = new FakeRest();
        AtomicInteger localSubmits = new AtomicInteger();
        LocalSubtaskExecutor local = (task, cancellation) -> {
            localSubmits.incrementAndGet();
            return true;
        };
        SubtaskOrchestrator orchestrator = orchestrator(rest, local);

        ToolResult result = orchestrator.execute(context("agent", SubtaskCapabilitySnapshot.empty(), null),
                call(MAPPER.createObjectNode().put("prompt", "local work").put("wait_for_completion", false)));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals(1, localSubmits.get());
        assertTrue(rest.getCount.get() > 0, "local execution must still await the platform terminal state");
        ObjectNode item = (ObjectNode) result.getResult().path("results").get(0);
        assertEquals("success", item.path("status").asText());
        assertFalse(item.path("detached").asBoolean(false));
    }

    @Test
    void explicitRemoteTargetNeverExecutesLocallyEvenWhenPending() {
        FakeRest rest = new FakeRest();
        rest.terminalImmediately = false;
        AtomicInteger localSubmits = new AtomicInteger();
        LocalSubtaskExecutor local = (task, cancellation) -> {
            localSubmits.incrementAndGet();
            return true;
        };
        SubtaskOrchestrator orchestrator = new SubtaskOrchestrator(rest, "client", "device", local,
                4, 1, Duration.ofSeconds(5), Duration.ofMillis(5));

        ToolResult result = orchestrator.execute(context("agent", SubtaskCapabilitySnapshot.empty(), null),
                call(MAPPER.createObjectNode().put("prompt", "remote").put("target_client_id", "other-client")
                        .put("timeout_seconds", 1)));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals(0, localSubmits.get(), "a task targeting another client must never run locally");
        assertTrue(rest.getCount.get() > 0, "remote-target task must be awaited on the platform");
        assertEquals("timeout", result.getResult().path("results").get(0).path("status").asText());
    }

    @Test
    void deviceMismatchRoutesRemotely() {
        FakeRest rest = new FakeRest();
        rest.terminalImmediately = false;
        AtomicInteger localSubmits = new AtomicInteger();
        LocalSubtaskExecutor local = (task, cancellation) -> {
            localSubmits.incrementAndGet();
            return true;
        };
        SubtaskOrchestrator orchestrator = new SubtaskOrchestrator(rest, "client", "device", local,
                4, 1, Duration.ofSeconds(5), Duration.ofMillis(5));

        ToolResult result = orchestrator.execute(context("agent", SubtaskCapabilitySnapshot.empty(), null),
                call(MAPPER.createObjectNode().put("prompt", "other device").put("target_device_id", "other-device")
                        .put("timeout_seconds", 1)));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals(0, localSubmits.get());
        assertEquals("timeout", result.getResult().path("results").get(0).path("status").asText());
    }

    @Test
    void localTargetAlreadyDispatchedIsAwaitedNotExecutedAgain() {
        FakeRest rest = new FakeRest();
        rest.terminalImmediately = false;
        AtomicInteger localSubmits = new AtomicInteger();
        LocalSubtaskExecutor local = (task, cancellation) -> {
            localSubmits.incrementAndGet();
            return true;
        };
        SubtaskOrchestrator orchestrator = new SubtaskOrchestrator(rest, "client", "device", local,
                4, 1, Duration.ofSeconds(5), Duration.ofMillis(5));

        ToolResult result = orchestrator.execute(context("agent", SubtaskCapabilitySnapshot.empty(), null),
                call(MAPPER.createObjectNode().put("prompt", "dispatched").put("delivery_policy", "remote")
                        .put("timeout_seconds", 1)));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals(0, localSubmits.get(), "platform-dispatched task must not run locally a second time");
        assertTrue(rest.getCount.get() > 0);
        assertEquals("timeout", result.getResult().path("results").get(0).path("status").asText());
    }

    @Test
    void detachedDispatchedLocalTargetSurvivesCancelDuringCreateWindow() throws Exception {
        FakeRest rest = new FakeRest();
        rest.terminalImmediately = false;
        rest.createGate = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger localSubmits = new AtomicInteger();
        LocalSubtaskExecutor local = (task, cancellation) -> {
            localSubmits.incrementAndGet();
            return true;
        };
        SubtaskOrchestrator orchestrator = new SubtaskOrchestrator(rest, "client", "device", local,
                4, 1, Duration.ofSeconds(30), Duration.ofMillis(5));
        ToolExecutionContext.CancellationSource cancellation = new ToolExecutionContext.CancellationSource();
        ToolExecutionContext context = context("agent", SubtaskCapabilitySnapshot.empty(), cancellation);

        CompletableFuture<ToolResult> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> future.complete(orchestrator.execute(context,
                call(MAPPER.createObjectNode().put("prompt", "detached local").put("delivery_policy", "remote")
                        .put("wait_for_completion", false).put("timeout_seconds", 20)))));
        long deadline = System.currentTimeMillis() + 2000;
        while (rest.createCount.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertEquals(1, rest.createCount.get());
        cancellation.cancel();
        rest.createGate.countDown();
        ToolResult result = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
        orchestrator.close();

        assertEquals("cancelled", result.getResult().path("results").get(0).path("status").asText());
        assertEquals(0, localSubmits.get());
        assertEquals(0, rest.cancellations.size(),
                "a dispatched detached task must survive parent cancellation even in the create window");
    }

    @Test
    void parentCancellationDoesNotCancelReturnedDetachedTasks() {
        FakeRest rest = new FakeRest();
        rest.terminalImmediately = false;
        SubtaskOrchestrator orchestrator = orchestrator(rest, null);
        ToolExecutionContext.CancellationSource cancellation = new ToolExecutionContext.CancellationSource();

        ToolResult result = orchestrator.execute(context("agent", SubtaskCapabilitySnapshot.empty(), cancellation),
                call(MAPPER.createObjectNode().put("prompt", "survive").put("delivery_policy", "remote")
                        .put("wait_for_completion", false)));

        assertTrue(result.isSuccess(), result.getError());
        cancellation.cancel();
        orchestrator.close();
        assertEquals(1, rest.createCount.get());
        assertEquals(0, rest.cancellations.size(), "detached tasks must survive parent cancellation and close");
    }

    @Test
    void batchDetachedCountsAcceptedAsSuccessful() {
        FakeRest rest = new FakeRest();
        rest.terminalImmediately = false;
        SubtaskOrchestrator orchestrator = orchestrator(rest, null);
        ObjectNode arguments = MAPPER.createObjectNode();
        arguments.putArray("subtasks").add("first").add("second");
        arguments.put("delivery_policy", "remote");
        arguments.put("wait_for_completion", false);
        arguments.put("max_parallelism", 2);

        ToolResult result = orchestrator.execute(context("agent", SubtaskCapabilitySnapshot.empty(), null), call(arguments));

        assertTrue(result.isSuccess(), result.getError());
        assertEquals(2, rest.createCount.get());
        assertEquals(0, rest.getCount.get());
        assertEquals(2, result.getResult().path("successful").asInt());
        assertTrue(result.getResult().path("results").get(0).path("detached").asBoolean());
        assertTrue(result.getResult().path("results").get(1).path("detached").asBoolean());
    }

    @Test
    void itemWaitForCompletionOverridesRootDetached() {
        FakeRest rest = new FakeRest();
        SubtaskOrchestrator orchestrator = orchestrator(rest, null);
        ObjectNode arguments = MAPPER.createObjectNode();
        ArrayNode subtasks = arguments.putArray("subtasks");
        subtasks.add("detach-me");
        subtasks.addObject().put("prompt", "wait-me").put("wait_for_completion", true);
        arguments.put("delivery_policy", "remote");
        arguments.put("wait_for_completion", false);

        ToolResult result = orchestrator.execute(context("agent", SubtaskCapabilitySnapshot.empty(), null), call(arguments));

        assertTrue(result.isSuccess(), result.getError());
        JsonNode detached = result.getResult().path("results").get(0);
        JsonNode waited = result.getResult().path("results").get(1);
        assertTrue(detached.path("detached").asBoolean());
        assertFalse(waited.path("detached").asBoolean(false));
        assertEquals("success", waited.path("status").asText());
        assertTrue(rest.getCount.get() > 0, "the item overriding wait_for_completion must be polled");
        assertEquals(2, result.getResult().path("successful").asInt());
    }

    @Test
    void subtaskInheritsParentModelWithoutExecutionMode() {
        FakeRest rest = new FakeRest();
        SubtaskOrchestrator orchestrator = orchestrator(rest, null);
        io.taiyiflow.sdk.transport.envelope.ChatRequest parent = new io.taiyiflow.sdk.transport.envelope.ChatRequest();
        parent.setModel("parent-model");
        parent.setExecutionMode("should-not-propagate");
        SubtaskCapabilitySnapshot snapshot = SubtaskCapabilitySnapshot.fromChatRequest(parent);

        ToolResult result = orchestrator.execute(context("agent", snapshot, null),
                call(MAPPER.createObjectNode().put("prompt", "inherit").put("delivery_policy", "remote")));

        assertTrue(result.isSuccess(), result.getError());
        ObjectNode payload = rest.created.get(0);
        assertEquals("parent-model", payload.path("command_payload").path("features").path("model").asText());
        assertFalse(payload.toString().contains("execution_mode"));

        SubtaskCapabilitySnapshot restored = SubtaskCapabilitySnapshot.fromFeatures(
                snapshot.toFeatures(), snapshot.availableTools());
        assertEquals("parent-model", restored.getModel());
        io.taiyiflow.sdk.transport.envelope.ChatRequest child = new io.taiyiflow.sdk.transport.envelope.ChatRequest();
        restored.apply(child);
        assertEquals("parent-model", child.getModel());
        assertNull(child.getExecutionMode());
    }

    @Test
    void snapshotIsImmutableAndContainsOnlyNames() {
        ObjectNode servers = MAPPER.createObjectNode();
        servers.putObject("safe").put("url", "https://secret-token.example");
        List<JsonNode> tools = new ArrayList<>();
        tools.add(MAPPER.getNodeFactory().textNode("search"));
        io.taiyiflow.sdk.transport.envelope.ChatRequest request = new io.taiyiflow.sdk.transport.envelope.ChatRequest();
        request.setClientMcpServers(servers);
        request.setRequestedSkillNames(new ArrayList<>(List.of("review")));
        request.setAvailableTools(tools);

        SubtaskCapabilitySnapshot snapshot = SubtaskCapabilitySnapshot.fromChatRequest(request);
        ((ObjectNode) request.getClientMcpServers()).putObject("later").put("token", "secret");
        String serialized = snapshot.toFeatures().toString();

        assertEquals(List.of("safe"), snapshot.getMcpServerNames());
        assertEquals(Set.of("search"), snapshot.getAllowedTools());
        assertFalse(serialized.contains("secret-token"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getMcpServerNames().add("other"));
    }

    private static SubtaskOrchestrator orchestrator(FakeRest rest, LocalSubtaskExecutor local) {
        return new SubtaskOrchestrator(rest, "client", "device", local,
                8, 2, Duration.ofSeconds(30), Duration.ofMillis(5));
    }

    private static ToolExecutionContext context(String mode, SubtaskCapabilitySnapshot snapshot,
                                                ToolExecutionContext.Cancellation cancellation) {
        return ToolExecutionContext.builder("flow", "parent-session")
                .runId("parent-run")
                .messageId("parent-message")
                .requestMode(mode)
                .capabilities(snapshot)
                .cancellation(cancellation == null ? ToolExecutionContext.Cancellation.none() : cancellation)
                .build();
    }

    private static ToolCall call(JsonNode arguments) {
        return new ToolCall("call-1", "run_subtask", arguments);
    }

    private static final class FakeRest extends RestClient {
        private final AtomicInteger createCount = new AtomicInteger();
        private final AtomicInteger listCount = new AtomicInteger();
        private final AtomicInteger getCount = new AtomicInteger();
        private final List<ObjectNode> created = new ArrayList<>();
        private final List<ObjectNode> cancellations = new ArrayList<>();
        private final Map<String, ObjectNode> tasks = new LinkedHashMap<>();
        private volatile boolean failCreateAfterPersist;
        private volatile boolean terminalImmediately = true;
        private volatile java.util.concurrent.CountDownLatch createGate;

        private FakeRest() {
            super("http://localhost", "token", Duration.ofSeconds(1));
        }

        @Override
        public synchronized JsonNode post(String path, JsonNode body) throws Exception {
            if (BASE.equals(path)) {
                createCount.incrementAndGet();
                ObjectNode task = ((ObjectNode) body).deepCopy();
                String id = "task-" + createCount.get();
                task.put("id", id);
                task.put("version", 1);
                // Backend create_and_dispatch_task: local_default stays pending (publisher executes);
                // any other delivery policy is dispatched synchronously at create time.
                boolean platformDispatched = !"local_default".equals(body.path("delivery_policy").asText("local_default"));
                task.put("status", !platformDispatched ? "pending" : terminalImmediately ? "success" : "dispatched");
                task.putObject("result_payload").put("text", body.path("command_payload").path("input_value").asText());
                created.add(((ObjectNode) body).deepCopy());
                tasks.put(id, task);
                if (failCreateAfterPersist) throw new java.net.http.HttpTimeoutException("lost response");
                java.util.concurrent.CountDownLatch gate = createGate;
                if (gate != null) gate.await();
                return task.deepCopy();
            }
            if (path.startsWith(BASE + "/") && path.endsWith("/cancel")) {
                cancellations.add(((ObjectNode) body).deepCopy());
                String id = path.substring((BASE + "/").length(), path.length() - "/cancel".length());
                ObjectNode task = tasks.get(id);
                task.put("version", task.path("version").asLong() + 1);
                return task.deepCopy();
            }
            throw new IllegalArgumentException("Unexpected POST " + path);
        }

        @Override
        public synchronized JsonNode get(String path) {
            if (path.startsWith(BASE + "?")) {
                listCount.incrementAndGet();
                ArrayNode result = MAPPER.createArrayNode();
                tasks.values().forEach(task -> result.add(task.deepCopy()));
                return result;
            }
            if (path.startsWith(BASE + "/")) {
                getCount.incrementAndGet();
                String id = path.substring((BASE + "/").length());
                ObjectNode task = tasks.get(id);
                if (task == null) return MAPPER.nullNode();
                ObjectNode snapshot = task.deepCopy();
                if (terminalImmediately
                        && !Set.of("success", "failed", "cancelled", "expired").contains(snapshot.path("status").asText())) {
                    snapshot.put("status", "success");
                }
                return snapshot;
            }
            throw new IllegalArgumentException("Unexpected GET " + path);
        }
    }
}
