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

public final class PlatformTaskReporter implements TaskReporter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        auth.executeWithRetry(() -> {
            if (isSchedule(task)) {
                ObjectNode report = MAPPER.createObjectNode().put("status", "running");
                return schedules.report(task.runRecordId, report);
            }
            return tasks.running(requireTaskId(task), progress);
        });
    }

    @Override
    public void success(CollaborationPlugin.TaskDispatch task, Object result) throws Exception {
        auth.executeWithRetry(() -> {
            if (isSchedule(task)) {
                ObjectNode report = MAPPER.createObjectNode().put("status", "success");
                report.set("result_payload", objectPayload(MAPPER.valueToTree(result), "result"));
                return schedules.report(task.runRecordId, report);
            }
            return tasks.success(requireTaskId(task), result);
        });
    }

    @Override
    public void failure(CollaborationPlugin.TaskDispatch task, String error) throws Exception {
        String message = error == null || error.isBlank() ? "Task execution failed" : error;
        auth.executeWithRetry(() -> {
            if (isSchedule(task)) {
                ObjectNode report = MAPPER.createObjectNode()
                        .put("status", "failed")
                        .put("error_message", message);
                return schedules.report(task.runRecordId, report);
            }
            return tasks.failure(requireTaskId(task), message);
        });
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

    private static ObjectNode objectPayload(JsonNode value, String field) {
        if (value != null && value.isObject()) {
            return (ObjectNode) value;
        }
        return MAPPER.createObjectNode().set(field, value == null ? MAPPER.nullNode() : value);
    }
}
