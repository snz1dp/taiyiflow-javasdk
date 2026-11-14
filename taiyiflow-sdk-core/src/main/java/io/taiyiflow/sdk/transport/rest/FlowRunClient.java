package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import io.taiyiflow.sdk.transport.envelope.JsonNodes;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class FlowRunClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> TERMINAL = Set.of("success", "failed", "cancelled");
    private static final Set<String> ACTIVE = Set.of("pending", "running");

    private final RestClient rest;
    private final AtomicBoolean closed = new AtomicBoolean();

    public FlowRunClient(RestClient rest) {
        this.rest = rest;
    }

    public RunResult run(String flowId, ChatRequest request) throws Exception {
        return run(flowId, request, null);
    }

    public RunResult run(String flowId, ChatRequest request, Duration requestTimeout) throws Exception {
        ObjectNode body = body(request, false);
        RestClient.RestResponse response = rest.postRaw(runPath(flowId), body, requestTimeout).requireSuccess();
        if (response.statusCode() == 202) {
            throw new IllegalStateException("Synchronous run unexpectedly returned an asynchronous task");
        }
        return RunResult.from(response);
    }

    public Task submitAsync(String flowId, ChatRequest request) throws Exception {
        return submitAsync(flowId, request, null);
    }

    public Task submitAsync(String flowId, ChatRequest request, Duration requestTimeout) throws Exception {
        ObjectNode body = body(request, true);
        RestClient.RestResponse response;
        try {
            response = rest.postRaw(runPath(flowId), body, requestTimeout);
        } catch (Exception exception) {
            throw new SubmissionUnknownException("Flow task submission outcome is unknown", exception);
        }
        if (response.statusCode() != 202) {
            response.requireSuccess();
            throw new SubmissionUnknownException("Flow task submission did not return HTTP 202", null);
        }
        JsonNode json = response.json();
        String taskId = json.path("task_id").asText(null);
        if (taskId == null || taskId.isBlank()) {
            throw new SubmissionUnknownException("Flow task submission returned no task_id", null);
        }
        return Task.from(json);
    }

    public Task poll(String taskId) throws Exception {
        return poll(taskId, null);
    }

    public Task poll(String taskId, Duration requestTimeout) throws Exception {
        requireTaskId(taskId);
        RestClient.RestResponse response = rest.getRaw("/api/v1/run/tasks/" + RestClient.pathSegment(taskId),
                requestTimeout).requireSuccess();
        return Task.from(response.json());
    }

    public Task cancel(String taskId) throws Exception {
        return cancel(taskId, null);
    }

    public Task cancel(String taskId, Duration requestTimeout) throws Exception {
        requireTaskId(taskId);
        RestClient.RestResponse response = rest.postRaw("/api/v1/run/tasks/" + RestClient.pathSegment(taskId)
                + "/cancel", MAPPER.createObjectNode(), requestTimeout).requireSuccess();
        if (response.statusCode() != 202) {
            throw new IllegalStateException("Task cancellation did not return HTTP 202");
        }
        return Task.from(response.json());
    }

    public Task await(String taskId, PollOptions options, BooleanSupplier cancellationRequested) throws Exception {
        long deadline = System.nanoTime() + options.overallTimeout().toNanos();
        boolean cancelRequested = false;
        while (!closed.get()) {
            if (!cancelRequested && cancellationRequested != null && cancellationRequested.getAsBoolean()) {
                try {
                    cancel(taskId, options.requestTimeout());
                } catch (Exception ignored) {
                    // The next poll determines whether cancellation took effect.
                }
                cancelRequested = true;
            }
            Task task = poll(taskId, options.requestTimeout());
            if (task.terminal()) {
                return task;
            }
            if (!ACTIVE.contains(task.status())) {
                throw new IllegalStateException("Unknown flow task status: " + task.status());
            }
            if (System.nanoTime() >= deadline) {
                if (!cancelRequested) {
                    try {
                        cancel(taskId, options.requestTimeout());
                    } catch (Exception ignored) {
                        // Timeout reporting must retain the original task even if cancellation is unknown.
                    }
                    cancelRequested = true;
                }
                throw new FlowRunTimeoutException(taskId, cancelRequested);
            }
            long remainingMillis = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
            Thread.sleep(Math.min(options.pollInterval().toMillis(), remainingMillis));
        }
        throw new IllegalStateException("Flow run client is closed");
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private static ObjectNode body(ChatRequest request, boolean async) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        ObjectNode body = MAPPER.convertValue(request, ObjectNode.class);
        body.put("async", async);
        return body;
    }

    private static String runPath(String flowId) {
        if (flowId == null || flowId.isBlank()) {
            throw new IllegalArgumentException("flowId is required");
        }
        return "/api/v1/run/" + RestClient.pathSegment(flowId);
    }

    private static void requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
    }

    public record PollOptions(Duration requestTimeout, Duration pollInterval, Duration overallTimeout) {
        public PollOptions {
            if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                    || pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                    || overallTimeout == null || overallTimeout.isNegative() || overallTimeout.isZero()) {
                throw new IllegalArgumentException("Flow run timeouts and poll interval must be positive");
            }
        }

        public static PollOptions defaults(Duration overallTimeout) {
            return new PollOptions(Duration.ofSeconds(30), Duration.ofSeconds(2), overallTimeout);
        }
    }

    public record RunResult(int httpStatus, String mediaType, String sessionId, String responseSessionHeader,
                            List<String> outputs, String rawBody, JsonNode json) {
        static RunResult from(RestClient.RestResponse response) throws Exception {
            JsonNode json = null;
            String mediaType = response.mediaType();
            if (mediaType != null && mediaType.toLowerCase(Locale.ROOT).contains("json")
                    && response.body().length > 0) {
                json = response.json();
                if (json.path("media_type").isTextual()) {
                    mediaType = json.path("media_type").asText();
                }
            }
            List<String> outputs = json != null && json.path("outputs").isArray()
                    ? JsonNodes.stream(json.path("outputs")).map(JsonNode::asText).toList() : List.of();
            return new RunResult(response.statusCode(), mediaType,
                    json == null ? null : json.path("session_id").asText(null),
                    response.header("x-session-id"), outputs, response.text(), json);
        }
    }

    public record Task(String taskId, String status, String flowId, String sessionId, String runId,
                       RunResult result, JsonNode error, JsonNode raw) {
        static Task from(JsonNode json) throws Exception {
            JsonNode resultNode = json.get("result");
            RunResult result = null;
            if (resultNode != null && resultNode.isObject()) {
                List<String> outputs = resultNode.path("outputs").isArray()
                        ? JsonNodes.stream(resultNode.path("outputs")).map(JsonNode::asText).toList() : List.of();
                result = new RunResult(200, resultNode.path("media_type").asText(null),
                        resultNode.path("session_id").asText(null), null, outputs,
                        MAPPER.writeValueAsString(resultNode), resultNode);
            }
            String taskId = json.path("task_id").asText(null);
            String status = json.path("status").asText(null);
            if (taskId == null || taskId.isBlank() || status == null || status.isBlank()) {
                throw new IllegalArgumentException("Flow task response requires task_id and status");
            }
            return new Task(taskId, status, json.path("flow_id").asText(null),
                    json.path("session_id").asText(null), json.path("run_id").asText(null),
                    result, json.get("error"), json.deepCopy());
        }

        public boolean terminal() {
            return TERMINAL.contains(status);
        }
    }

    public static class SubmissionUnknownException extends Exception {
        public SubmissionUnknownException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class FlowRunTimeoutException extends Exception {
        private final String taskId;
        private final boolean cancellationRequested;

        FlowRunTimeoutException(String taskId, boolean cancellationRequested) {
            super("Flow task did not reach a terminal status before timeout" + (cancellationRequested
                    ? " after cancellation was requested" : ""));
            this.taskId = taskId;
            this.cancellationRequested = cancellationRequested;
        }

        public String taskId() { return taskId; }
        public boolean cancellationRequested() { return cancellationRequested; }
    }
}
