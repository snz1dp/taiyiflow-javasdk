package io.taiyiflow.sdk.core.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import io.taiyiflow.sdk.transport.rest.CollaborationTaskClient;
import io.taiyiflow.sdk.transport.rest.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class SubtaskOrchestrator implements PluginManager.ContextualToolHandler, AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Integer> MODE_RANK = Map.of("ask", 0, "plan", 1, "agent", 2);
    // Electron _shouldRunRemotely second layer: these platform statuses mean another executor already owns the task.
    private static final Set<String> DISPATCHED_STATUSES =
            Set.of("dispatched", "acknowledged", "running", "success", "failed");

    private final CollaborationTaskClient tasks;
    private final String localClientId;
    private final String localDeviceId;
    private final LocalSubtaskExecutor localExecutor;
    private final int maxTasks;
    private final int maxParallelism;
    private final Duration maxTimeout;
    private final Duration pollInterval;
    private final ConcurrentLinkedQueue<CallState> active = new ConcurrentLinkedQueue<>();

    public SubtaskOrchestrator(RestClient rest, String localClientId, String localDeviceId,
                               LocalSubtaskExecutor localExecutor) {
        this(rest, localClientId, localDeviceId, localExecutor, 16, 4,
                Duration.ofHours(1), Duration.ofMillis(250));
    }

    public SubtaskOrchestrator(RestClient rest, String localClientId, String localDeviceId,
                               LocalSubtaskExecutor localExecutor, int maxTasks, int maxParallelism,
                               Duration maxTimeout, Duration pollInterval) {
        if (maxTasks < 1 || maxParallelism < 1 || maxTimeout.isNegative() || maxTimeout.isZero()
                || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("Invalid subtask limits");
        }
        this.tasks = new CollaborationTaskClient(rest);
        this.localClientId = localClientId;
        this.localDeviceId = localDeviceId;
        this.localExecutor = localExecutor;
        this.maxTasks = maxTasks;
        this.maxParallelism = Math.min(maxParallelism, maxTasks);
        this.maxTimeout = maxTimeout;
        this.pollInterval = pollInterval;
    }

    public void register(PluginManager plugins) {
        plugins.register("run_subtask", this, maxTimeout);
        plugins.registerCloseable(this);
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, ToolCall call) {
        ConcurrentLinkedQueue<CallState> states = new ConcurrentLinkedQueue<>();
        ToolExecutionContext.CancellationRegistration registration = context.getCancellation()
                .onCancel(() -> cancelAll(states));
        try {
            List<Request> requests = parseRequests(call.getArguments(), context);
            int parallelism = Math.min(maxParallelism, requestedParallelism(call.getArguments(), requests.size()));
            Semaphore permits = new Semaphore(parallelism);
            List<CompletableFuture<ObjectNode>> futures = new ArrayList<>();
            for (int index = 0; index < requests.size(); index++) {
                Request request = requests.get(index);
                int resultIndex = index;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        permits.acquire();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return failure(resultIndex, null, "interrupted", "Subtask scheduling interrupted");
                    }
                    CallState state = new CallState(resultIndex);
                    // Remote-target detached requests stay out of the parent cancellation chain.
                    // Local-target requests register conservatively (dispatch status is unknown
                    // before create); executeOne deregisters them if they turn out detached.
                    boolean detached = !request.waitForCompletion() && !targetsCurrentClient(request);
                    if (!detached) {
                        states.add(state);
                        active.add(state);
                    }
                    try {
                        return executeOne(context, request, state, states);
                    } finally {
                        active.remove(state);
                        states.remove(state);
                        permits.release();
                    }
                }, runnable -> Thread.ofVirtual().start(runnable)));
            }
            ArrayNode results = MAPPER.createArrayNode();
            for (CompletableFuture<ObjectNode> future : futures) results.add(future.join());
            ObjectNode output = MAPPER.createObjectNode();
            output.set("results", results);
            output.put("count", results.size());
            output.put("successful", countSuccessful(results));
            return ToolResult.success(call.getId(), output);
        } catch (IllegalArgumentException exception) {
            return ToolResult.error(call.getId(), exception.getMessage());
        } catch (Exception exception) {
            cancelAll(states);
            return ToolResult.error(call.getId(), "Subtask orchestration failed: " + exception.getMessage());
        } finally {
            registration.close();
        }
    }

    private ObjectNode executeOne(ToolExecutionContext context, Request request, CallState state,
                                  ConcurrentLinkedQueue<CallState> states) {
        if (context.getCancellation().isCancellationRequested()) {
            return failure(state.index, null, "cancelled", "Parent execution was cancelled");
        }
        String batchId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        String childSessionId = UUID.randomUUID().toString();
        String childRunId = UUID.randomUUID().toString();
        ObjectNode payload = createPayload(context, request, idempotencyKey, batchId, childSessionId, childRunId);
        JsonNode created = null;
        boolean reconcile = false;
        try {
            created = tasks.create(payload);
            // A 200 with an invalid body may still have persisted the task; reconcile instead of guessing.
            if (!isValidTaskRecord(created)) reconcile = true;
        } catch (Exception exception) {
            reconcile = true;
        }
        if (reconcile) {
            try {
                created = findCreated(context.getRunId(), idempotencyKey, childSessionId, childRunId);
            } catch (Exception reconciliationFailure) {
                return failure(state.index, null, "unknown",
                        "Task creation outcome is unknown; reconciliation failed");
            }
            if (!isValidTaskRecord(created)) {
                return failure(state.index, null, "unknown",
                        "Task creation outcome is unknown; no matching platform task was found");
            }
        }
        boolean localTarget = targetsCurrentClient(request);
        // Electron/CLI second routing layer: a task the platform already dispatched is owned by
        // another executor (possibly this worker's own task.dispatch path), so wait for the
        // platform result instead of executing it locally a second time.
        boolean runLocal = localTarget && !DISPATCHED_STATUSES.contains(text(created, "status"));
        boolean detached = !request.waitForCompletion() && !runLocal;
        if (detached) {
            // Leave the parent cancellation chain before publishing taskId so a concurrent
            // cancelAll cannot reach a task that is about to be returned as detached.
            states.remove(state);
            active.remove(state);
        }
        state.taskId = text(created, "id");
        state.version.set(created.path("version").asLong(0));
        // A cancel may arrive during the creation REST window, before any executor is registered.
        if (context.getCancellation().isCancellationRequested()) {
            // Detached platform tasks survive parent cancellation; only report the cancelled call.
            if (!detached) cancel(state);
            return failure(state.index, state.taskId, "cancelled", "Parent execution was cancelled");
        }
        if (detached) {
            // Electron detached semantics: the platform task keeps running after this call returns.
            // Detached states were deregistered from states/active above, so parent cancel/close
            // cannot reach them; execute()'s finally removes any still-registered state.
            ObjectNode result = baseResult(state.index, state.taskId, text(created, "status"));
            result.put("detached", true);
            result.put("task_kind", "dialogue_subtask");
            result.put("child_session_id", childSessionId);
            result.put("child_run_id", childRunId);
            result.put("source_session_id", context.getSessionId());
            result.put("source_run_id", context.getRunId());
            if (context.getMessageId() != null) result.put("source_message_id", context.getMessageId());
            return result;
        }
        try {
            if (runLocal) {
                if (localExecutor == null) throw new IllegalStateException("No local subtask executor is configured");
                ObjectNode command = payload.deepCopy();
                command.put("task_id", state.taskId);
                command.put("task_kind", "dialogue_subtask");
                command.put("version", state.version.get());
                command.put("source_session_id", context.getSessionId());
                command.put("source_run_id", context.getRunId());
                command.put("source_message_id", context.getMessageId());
                command.put("child_session_id", childSessionId);
                command.put("child_run_id", childRunId);
                command.put("correlation_id", text(payload, "correlation_id"));
                command.put("idempotency_key", idempotencyKey);
                command.set("command_payload", payload.path("command_payload").deepCopy());
                if (!localExecutor.submit(SubtaskTask.fromPlatform(command), context.getCancellation())) {
                    throw new IllegalStateException("Local subtask execution capacity is exhausted");
                }
            }
            return awaitTerminal(state, request.timeoutMillis(), context.getCancellation());
        } catch (Exception exception) {
            cancel(state);
            String status = context.getCancellation().isCancellationRequested() ? "cancelled" : "failed";
            return failure(state.index, state.taskId, status, safeMessage(exception));
        }
    }

    private ObjectNode awaitTerminal(CallState state, long timeoutMillis,
                                     ToolExecutionContext.Cancellation cancellation) throws Exception {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        state.waiter = Thread.currentThread();
        try {
            while (System.nanoTime() < deadline) {
                if (cancellation.isCancellationRequested()) {
                    cancel(state);
                    return failure(state.index, state.taskId, "cancelled", "Parent execution was cancelled");
                }
                JsonNode current;
                try {
                    current = tasks.get(state.taskId);
                } catch (Exception exception) {
                    if (cancellation.isCancellationRequested()) {
                        return failure(state.index, state.taskId, "cancelled", "Parent execution was cancelled");
                    }
                    throw exception;
                }
                state.version.set(current.path("version").asLong(state.version.get()));
                String status = text(current, "status");
                if ("success".equals(status) || "succeeded".equals(status) || "completed".equals(status)) {
                    ObjectNode result = baseResult(state.index, state.taskId, "success");
                    result.set("result", current.path("result_payload").deepCopy());
                    return result;
                }
                if ("failed".equals(status) || "cancelled".equals(status) || "expired".equals(status)) {
                    return failure(state.index, state.taskId, status,
                            text(current, "error_message") == null ? "Subtask " + status : text(current, "error_message"));
                }
                try {
                    Thread.sleep(pollInterval.toMillis());
                } catch (InterruptedException exception) {
                    // cancel() interrupts the waiter so polling stops without waiting out the interval.
                    if (!cancellation.isCancellationRequested()) {
                        Thread.currentThread().interrupt();
                        throw exception;
                    }
                    cancel(state);
                    return failure(state.index, state.taskId, "cancelled", "Parent execution was cancelled");
                }
            }
        } finally {
            state.waiter = null;
        }
        cancel(state);
        return failure(state.index, state.taskId, "timeout", "Subtask did not finish before its deadline");
    }

    private List<Request> parseRequests(JsonNode arguments, ToolExecutionContext context) {
        if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException("run_subtask arguments must be an object");
        List<JsonNode> items = new ArrayList<>();
        if (arguments.has("subtasks")) {
            if (!arguments.path("subtasks").isArray() || arguments.path("subtasks").isEmpty()) {
                throw new IllegalArgumentException("subtasks must be a non-empty array");
            }
            arguments.path("subtasks").forEach(items::add);
        } else {
            items.add(arguments);
        }
        if (items.size() > maxTasks) throw new IllegalArgumentException("Too many subtasks; maximum is " + maxTasks);
        String parentMode = context.getRequestMode();
        if (!MODE_RANK.containsKey(parentMode)) parentMode = "agent";
        List<Request> result = new ArrayList<>();
        for (JsonNode item : items) {
            ObjectNode value = item.isTextual() ? MAPPER.createObjectNode().put("prompt", item.asText()) :
                    item.isObject() ? (ObjectNode) item : null;
            if (value == null) throw new IllegalArgumentException("Each subtask must be a prompt or object");
            String prompt = text(value, "prompt");
            if (prompt == null) throw new IllegalArgumentException("Subtask prompt is required");
            String explicitMode = explicitMode(value, arguments);
            String mode = resolveMode(parentMode, explicitMode);
            String flowId = firstNonBlank(text(value, "flow_id"), text(arguments, "flow_id"), context.getFlowId());
            String targetClient = firstNonBlank(text(value, "target_client_id"), text(arguments, "target_client_id"), localClientId);
            String targetDevice = firstNonBlank(text(value, "target_device_id"), text(arguments, "target_device_id"), localDeviceId);
            String delivery = firstNonBlank(text(value, "delivery_policy"), text(arguments, "delivery_policy"), "local_default");
            long timeoutSeconds = positiveLong(value.has("timeout_seconds") ? value : arguments, "timeout_seconds",
                    Math.min(300, maxTimeout.toSeconds()));
            if (timeoutSeconds > maxTimeout.toSeconds()) throw new IllegalArgumentException("Subtask timeout exceeds configured maximum");
            result.add(new Request(prompt, flowId, mode, targetClient, targetDevice, delivery, timeoutSeconds * 1000,
                    waitForCompletion(value, arguments)));
        }
        return result;
    }

    private ObjectNode createPayload(ToolExecutionContext context, Request request, String idempotencyKey,
                                     String batchId, String childSessionId, String childRunId) {
        if (context.getRunId() == null || context.getRunId().isBlank()) {
            throw new IllegalArgumentException("A persisted parent run is required to create a subtask");
        }
        ObjectNode command = MAPPER.createObjectNode()
                .put("input_value", request.prompt())
                .put("flow_id", request.flowId())
                .put("request_mode", request.mode())
                .put("parent_id", context.getRunId())
                .put("wait_for_completion", request.waitForCompletion());
        SubtaskCapabilitySnapshot capabilities = context.getCapabilities();
        command.set("features", capabilities.toFeatures());
        command.set("available_tools", capabilities.availableTools());
        ObjectNode payload = MAPPER.createObjectNode();
        payload.set("command_payload", command);
        payload.put("task_kind", "dialogue_subtask");
        if (request.targetClientId() != null) payload.put("target_client_id", request.targetClientId());
        if (request.targetDeviceId() != null) payload.put("target_device_id", request.targetDeviceId());
        payload.put("delivery_policy", request.deliveryPolicy());
        payload.put("source_session_id", context.getSessionId());
        payload.put("source_run_id", context.getRunId());
        if (context.getMessageId() != null) payload.put("source_message_id", context.getMessageId());
        payload.put("child_session_id", childSessionId);
        payload.put("child_run_id", childRunId);
        payload.put("correlation_id", UUID.randomUUID().toString());
        payload.put("idempotency_key", idempotencyKey);
        payload.put("batch_id", batchId);
        return payload;
    }

    private JsonNode findCreated(String sourceRunId, String idempotencyKey,
                                 String childSessionId, String childRunId) throws Exception {
        JsonNode listed = tasks.list(Map.of("source_run_id", sourceRunId,
                "task_kind", "dialogue_subtask", "active_only", false, "limit", 100));
        JsonNode values = listed != null && listed.isArray() ? listed : listed == null ? null : listed.get("items");
        if (values == null || !values.isArray()) return null;
        for (JsonNode value : values) {
            if (idempotencyKey.equals(text(value, "idempotency_key"))
                    || (childSessionId.equals(text(value, "child_session_id"))
                    && childRunId.equals(text(value, "child_run_id")))) return value;
        }
        return null;
    }

    private boolean targetsCurrentClient(Request request) {
        // Electron _subtaskTargetsCurrentClient: an explicit client target must match the local
        // client id (unknown local id means not-local); a device target only mismatches when the
        // local device id is known. delivery_policy is platform-side dispatch, not routing.
        String targetClient = request.targetClientId();
        if (targetClient != null && (localClientId == null || !localClientId.equals(targetClient))) {
            return false;
        }
        String targetDevice = request.targetDeviceId();
        return targetDevice == null || localDeviceId == null || localDeviceId.equals(targetDevice);
    }

    private static boolean isValidTaskRecord(JsonNode value) {
        return value != null && value.isObject() && text(value, "id") != null;
    }

    private void cancelAll(ConcurrentLinkedQueue<CallState> states) {
        states.forEach(this::cancel);
    }

    private void cancel(CallState state) {
        if (state == null || state.taskId == null || !state.cancelRequested.compareAndSet(false, true)) return;
        Thread waiter = state.waiter;
        if (waiter != null) {
            waiter.interrupt();
        }
        String requestId = UUID.randomUUID().toString();
        try {
            JsonNode cancelled = tasks.cancel(state.taskId, requestId, state.version.get());
            if (cancelled != null && cancelled.isObject()) {
                state.version.set(cancelled.path("version").asLong(state.version.get()));
            }
            if (localExecutor != null) localExecutor.cancel(new SubtaskTask(state.taskId, state.version.get(), null));
        } catch (Exception ignored) {
            // The platform remains authoritative; a failed cancellation is not reported as successful.
        }
    }

    private int requestedParallelism(JsonNode arguments, int count) {
        if (arguments.path("serial").asBoolean(false)) return 1;
        long configured = positiveLong(arguments, "max_parallelism", Math.min(count, maxParallelism));
        if (configured > maxParallelism) throw new IllegalArgumentException("max_parallelism exceeds configured maximum");
        return (int) Math.min(configured, count);
    }

    private static String explicitMode(JsonNode value, JsonNode root) {
        JsonNode raw = value.has("request_mode") ? value.get("request_mode") : root.get("request_mode");
        if (raw == null || raw.isNull()) return null;
        if (!raw.isTextual() || raw.asText().isBlank()) {
            throw new IllegalArgumentException("request_mode must be ask, plan or agent");
        }
        return raw.asText();
    }

    private static String resolveMode(String parentMode, String explicit) {
        if (explicit == null) return parentMode;
        Integer childRank = MODE_RANK.get(explicit);
        if (childRank == null) throw new IllegalArgumentException("Invalid request_mode: " + explicit);
        if (childRank > MODE_RANK.get(parentMode)) {
            throw new IllegalArgumentException("Subtask request_mode cannot exceed the parent mode");
        }
        return explicit;
    }

    private static ObjectNode baseResult(int index, String taskId, String status) {
        ObjectNode result = MAPPER.createObjectNode().put("index", index).put("status", status);
        if (taskId != null) result.put("task_id", taskId);
        return result;
    }

    private static ObjectNode failure(int index, String taskId, String status, String error) {
        ObjectNode result = baseResult(index, taskId, status);
        result.put("error", error == null ? "Subtask failed" : error);
        return result;
    }

    private static int countSuccessful(ArrayNode results) {
        int count = 0;
        for (JsonNode result : results) {
            if ("success".equals(result.path("status").asText()) || result.path("detached").asBoolean(false)) count++;
        }
        return count;
    }

    private static boolean waitForCompletion(JsonNode value, JsonNode root) {
        JsonNode raw = value.has("wait_for_completion") ? value.get("wait_for_completion")
                : root.get("wait_for_completion");
        return raw == null || !raw.isBoolean() || raw.booleanValue();
    }

    private static long positiveLong(JsonNode value, String field, long fallback) {
        JsonNode item = value.get(field);
        if (item == null || item.isNull()) return fallback;
        if (!item.canConvertToLong() || item.asLong() < 1) throw new IllegalArgumentException(field + " must be positive");
        return item.asLong();
    }

    private static String text(JsonNode value, String field) {
        JsonNode item = value == null ? null : value.get(field);
        return item != null && item.isTextual() && !item.asText().isBlank() ? item.asText() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @Override
    public void close() {
        active.forEach(this::cancel);
        active.clear();
    }

    private record Request(String prompt, String flowId, String mode, String targetClientId,
                           String targetDeviceId, String deliveryPolicy, long timeoutMillis,
                           boolean waitForCompletion) {
    }

    private static final class CallState {
        private final int index;
        private volatile String taskId;
        private volatile Thread waiter;
        private final AtomicLong version = new AtomicLong();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();

        private CallState(int index) {
            this.index = index;
        }
    }
}
