package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.collaboration.CollaborationPlugin;
import io.taiyiflow.sdk.transport.rest.CollaborationTaskClient;
import io.taiyiflow.sdk.transport.rest.RestClient;
import io.taiyiflow.sdk.transport.rest.ScheduleClient;
import io.taiyiflow.sdk.worker.auth.WorkerAuth;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

public final class PlatformTaskReporter implements TaskReporter {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "success", "failed", "cancelled", "timeout", "offline");

    private final WorkerAuth auth;
    private final CollaborationTaskClient tasks;
    private final ScheduleClient schedules;

    public PlatformTaskReporter(String baseUrl, WorkerAuth auth, Duration timeout,
                                Map<String, String> credentialHeaders) {
        this(auth, new RestClient(baseUrl, auth::accessToken, timeout, credentialHeaders));
    }

    PlatformTaskReporter(WorkerAuth auth, RestClient rest) {
        this(auth, new CollaborationTaskClient(rest), new ScheduleClient(rest));
    }

    PlatformTaskReporter(WorkerAuth auth, CollaborationTaskClient tasks, ScheduleClient schedules) {
        this.auth = auth;
        this.tasks = tasks;
        this.schedules = schedules;
    }

    @Override
    public void running(CollaborationPlugin.TaskDispatch task, JsonNode progress) throws Exception {
        if (isSchedule(task)) {
            ObjectNode report = MAPPER.createObjectNode().put("status", "running");
            reportSchedule(task, "running", null, null, report);
            return;
        }
        reportTask(task, "running", null, progress, null);
    }

    @Override
    public void success(CollaborationPlugin.TaskDispatch task, Object result) throws Exception {
        ObjectNode payload = objectPayload(MAPPER.valueToTree(result), "result");
        if (isSchedule(task)) {
            ObjectNode report = MAPPER.createObjectNode().put("status", "success");
            report.set("result_payload", payload);
            reportSchedule(task, "success", payload, null, report);
            return;
        }
        reportTask(task, "success", payload, null, null);
    }

    @Override
    public void failure(CollaborationPlugin.TaskDispatch task, String error) throws Exception {
        String message = error == null || error.isBlank() ? "Task execution failed" : error;
        if (isSchedule(task)) {
            ObjectNode report = MAPPER.createObjectNode()
                    .put("status", "failed")
                    .put("error_message", message);
            reportSchedule(task, "failed", null, message, report);
            return;
        }
        reportTask(task, "failed", null, null, message);
    }

    @Override
    public void cancelled(CollaborationPlugin.TaskDispatch task, String cancelRequestId) throws Exception {
        if (isSchedule(task)) {
            ObjectNode report = MAPPER.createObjectNode().put("status", "cancelled");
            report.putObject("status_payload").put("cancel_request_id", cancelRequestId);
            reportSchedule(task, "cancelled", null, null, report);
            return;
        }
        reportTask(task, "cancelled", null, null, cancelRequestId);
    }

    private void reportTask(CollaborationPlugin.TaskDispatch task, String status, ObjectNode result,
                            JsonNode progress, String detail) throws Exception {
        String taskId = requireTaskId(task);
        auth.executeWithRetry(() -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                JsonNode snapshot = tasks.get(taskId);
                if (matchesTerminal(snapshot, status, result, progress, detail)) {
                    return null;
                }
                requireMutable(snapshot, taskId, status);
                try {
                    return switch (status) {
                        case "running" -> tasks.running(taskId, progress, version(snapshot));
                        case "success" -> tasks.success(taskId, result, version(snapshot));
                        case "failed" -> tasks.failure(taskId, detail, version(snapshot));
                        case "cancelled" -> tasks.cancelled(taskId, detail, version(snapshot));
                        default -> throw new IllegalArgumentException("Unsupported task report status " + status);
                    };
                } catch (RestClient.RestException exception) {
                    if (exception.getStatusCode() != 409 || attempt == 1) {
                        throw exception;
                    }
                }
            }
            return null;
        });
    }

    private void reportSchedule(CollaborationPlugin.TaskDispatch task, String status, ObjectNode result,
                                String error, ObjectNode report) throws Exception {
        String runRecordId = requireRunRecordId(task);
        auth.executeWithRetry(() -> {
            JsonNode snapshot = schedules.getRun(runRecordId);
            if (matchesTerminal(snapshot, status, result, null,
                    "cancelled".equals(status) ? cancelRequestId(report) : error)) {
                return null;
            }
            requireMutable(snapshot, runRecordId, status);
            return schedules.report(runRecordId, report);
        });
    }

    private static boolean matchesTerminal(JsonNode snapshot, String targetStatus, JsonNode result,
                                           JsonNode progress, String detail) {
        if (snapshot == null || !TERMINAL_STATUSES.contains(snapshot.path("status").asText())) {
            return false;
        }
        if (!targetStatus.equals(snapshot.path("status").asText())) {
            return false;
        }
        return switch (targetStatus) {
            case "success" -> snapshot.path("result_payload").equals(result);
            case "failed" -> snapshot.path("error_message").asText("").equals(detail);
            case "cancelled" -> {
                String expected = detail == null ? "" : detail;
                String actual = snapshot.path("cancel_request_id").asText("");
                if (actual.isEmpty()) {
                    actual = snapshot.path("status_payload").path("cancel_request_id").asText("");
                }
                yield expected.equals(actual);
            }
            default -> true;
        };
    }

    private static void requireMutable(JsonNode snapshot, String id, String targetStatus)
            throws ReportConflictException {
        if (snapshot != null && TERMINAL_STATUSES.contains(snapshot.path("status").asText())) {
            throw new ReportConflictException(id, snapshot.path("status").asText(), targetStatus);
        }
    }

    private static Long version(JsonNode snapshot) {
        return snapshot != null && snapshot.hasNonNull("version") ? snapshot.required("version").asLong() : null;
    }

    private static String cancelRequestId(ObjectNode report) {
        return report.path("status_payload").path("cancel_request_id").asText("");
    }

    private static boolean isSchedule(CollaborationPlugin.TaskDispatch task) {
        return "schedule".equals(task.taskType);
    }

    private static String requireTaskId(CollaborationPlugin.TaskDispatch task) {
        if (task.taskId == null || task.taskId.isBlank()) {
            throw new IllegalArgumentException("Platform reporting requires a collaboration taskId");
        }
        return task.taskId;
    }

    private static String requireRunRecordId(CollaborationPlugin.TaskDispatch task) {
        if (task.runRecordId == null || task.runRecordId.isBlank()) {
            throw new IllegalArgumentException("Platform reporting requires a schedule runRecordId");
        }
        return task.runRecordId;
    }

    private static ObjectNode objectPayload(JsonNode value, String field) {
        if (value != null && value.isObject()) {
            return (ObjectNode) value;
        }
        return MAPPER.createObjectNode().set(field, value == null ? MAPPER.nullNode() : value);
    }

    public static final class ReportConflictException extends Exception {
        private ReportConflictException(String id, String platformStatus, String targetStatus) {
            super("Platform task " + id + " is already " + platformStatus + "; cannot report " + targetStatus);
        }
    }
}
