package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.collaboration.CollaborationPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TaskExecutor {
    private final WorkerTaskHandler handler;
    private final TaskReporter reporter;
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();

    public TaskExecutor(WorkerTaskHandler handler, TaskReporter reporter) {
        this.handler = handler;
        this.reporter = reporter;
    }

    public TaskExecutionState execute(CollaborationPlugin.TaskDispatch task) {
        String key = keyOf(task);
        Execution execution = new Execution();
        Execution previous = executions.putIfAbsent(key, execution);
        if (previous != null) {
            return previous.state;
        }
        synchronized (execution) {
            try {
                reporter.running(task, null);
            } catch (Exception exception) {
                execution.state = TaskExecutionState.REPORT_UNKNOWN;
                return execution.state;
            }
            execution.state = TaskExecutionState.RUNNING;
            final Object result;
            try {
                result = handler.execute(task, progress -> reporter.running(task, progress));
            } catch (Exception exception) {
                return completeFailure(task, execution, exception.getMessage());
            }
            try {
                reporter.success(task, result);
                execution.state = TaskExecutionState.SUCCESS;
            } catch (Exception exception) {
                execution.state = TaskExecutionState.REPORT_UNKNOWN;
            }
            return execution.state;
        }
    }

    public TaskExecutionState stateOf(CollaborationPlugin.TaskDispatch task) {
        Execution execution = executions.get(keyOf(task));
        return execution == null ? null : execution.state;
    }

    public void reconcile(JsonNode snapshot) {
        String taskId = snapshot.path("task_id").asText("");
        if (taskId.isBlank()) {
            return;
        }
        String key = "task:" + taskId;
        TaskExecutionState platformState = switch (snapshot.path("status").asText("")) {
            case "success" -> TaskExecutionState.SUCCESS;
            case "failed", "offline", "timeout" -> TaskExecutionState.FAILED;
            case "cancelled" -> TaskExecutionState.CANCELLED;
            case "dispatched", "acknowledged", "running" -> TaskExecutionState.REPORT_UNKNOWN;
            default -> null;
        };
        if (platformState == null) {
            return;
        }
        executions.compute(key, (ignored, current) -> {
            if (current != null && (current.state == TaskExecutionState.RUNNING
                    || current.state == TaskExecutionState.SUCCESS
                    || current.state == TaskExecutionState.FAILED
                    || current.state == TaskExecutionState.CANCELLED)) {
                return current;
            }
            Execution reconciled = current == null ? new Execution() : current;
            reconciled.state = platformState;
            return reconciled;
        });
    }

    private TaskExecutionState completeFailure(CollaborationPlugin.TaskDispatch task,
                                               Execution execution, String error) {
        try {
            reporter.failure(task, error);
            execution.state = TaskExecutionState.FAILED;
        } catch (Exception reportError) {
            execution.state = TaskExecutionState.REPORT_UNKNOWN;
        }
        return execution.state;
    }

    private static String keyOf(CollaborationPlugin.TaskDispatch task) {
        if ("schedule".equals(task.taskType)) {
            if (task.runRecordId == null || task.runRecordId.isBlank()) {
                throw new IllegalArgumentException("Schedule task requires runRecordId");
            }
            return "schedule:" + task.runRecordId;
        }
        if (task.taskId == null || task.taskId.isBlank()) {
            throw new IllegalArgumentException("Collaboration task requires taskId");
        }
        return "task:" + task.taskId;
    }

    private static final class Execution {
        private volatile TaskExecutionState state = TaskExecutionState.RUNNING;
    }
}
