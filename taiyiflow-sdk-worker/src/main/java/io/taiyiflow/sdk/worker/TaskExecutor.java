package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.collaboration.CollaborationPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

public final class TaskExecutor {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskExecutor.class);
    private static final int DEFAULT_REGISTRY_CAPACITY = 1024;

    private final WorkerTaskHandler handler;
    private final TaskReporter reporter;
    private final int registryCapacity;
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();
    // Cancels may arrive before the dispatch is registered (no execution to abort yet);
    // remember them so prepare() can refuse to start, mirroring Electron's cancel registry.
    private final Map<String, CollaborationPlugin.TaskCancel> pendingCancels =
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CollaborationPlugin.TaskCancel> eldest) {
                    if (size() > registryCapacity) {
                        // Eviction drops a cancel that arrived before its dispatch; surface it so the
                        // lost cancellation is observable rather than silently allowing the task to run.
                        log.warn("Pending cancel registry exceeded capacity {}; evicting oldest cancel for {} "
                                + "which may now start unchecked", registryCapacity, eldest.getKey());
                        return true;
                    }
                    return false;
                }
            });

    public TaskExecutor(WorkerTaskHandler handler, TaskReporter reporter) {
        this(handler, reporter, DEFAULT_REGISTRY_CAPACITY);
    }

    TaskExecutor(WorkerTaskHandler handler, TaskReporter reporter, int registryCapacity) {
        if (registryCapacity <= 0) {
            throw new IllegalArgumentException("Task registry capacity must be positive");
        }
        this.handler = handler;
        this.reporter = reporter;
        this.registryCapacity = registryCapacity;
    }

    public TaskExecutionState execute(CollaborationPlugin.TaskDispatch task) {
        return executePrepared(prepare(task));
    }

    Reservation prepare(CollaborationPlugin.TaskDispatch task) {
        String key = keyOf(task);
        evictTerminalIfNecessary();
        Execution proposed = new Execution(key, task);
        if (executions.size() >= registryCapacity && !executions.containsKey(key)) {
            return new Reservation(proposed, false);
        }
        Execution execution = executions.compute(key, (ignored, current) -> {
            if (current == null || (current.isTerminal() && task.version > current.task.version)) {
                proposed.accepted = true;
                return proposed;
            }
            return current;
        });
        boolean accepted = execution == proposed && proposed.accepted;
        if (accepted) {
            applyPendingCancel(execution);
        }
        return new Reservation(execution, accepted);
    }

    private void applyPendingCancel(Execution execution) {
        CollaborationPlugin.TaskCancel pending = pendingCancels.remove(execution.key);
        if (pending == null) {
            return;
        }
        synchronized (execution) {
            if (pending.version > 0 && execution.task.version > 0
                    && pending.version < execution.task.version) {
                return;
            }
            if (pending.version > execution.task.version) {
                execution.task.version = pending.version;
            }
            execution.cancelRequested = true;
            execution.cancelRequestId = pending.cancelRequestId;
        }
    }

    void attachFuture(Reservation reservation, Future<?> future) {
        Execution execution = reservation.execution;
        boolean cancel;
        boolean handlerStarted;
        synchronized (execution) {
            execution.future = future;
            cancel = execution.cancelRequested;
            handlerStarted = execution.handlerStarted;
        }
        if (cancel) {
            // Same interrupt semantics as cancel(): only interrupt a handler that already started.
            future.cancel(handlerStarted);
            if (!handlerStarted) {
                completeCancellation(execution);
            }
        }
    }

    void reject(Reservation reservation) {
        if (reservation.accepted) {
            executions.remove(reservation.execution.key, reservation.execution);
        }
    }

    TaskExecutionState executePrepared(Reservation reservation) {
        Execution execution = reservation.execution;
        if (!reservation.accepted) {
            return execution.state;
        }
        synchronized (execution) {
            if (!execution.cancelRequested && execution.state != TaskExecutionState.CANCELLED) {
                if (execution.state != TaskExecutionState.QUEUED) {
                    return execution.state;
                }
                execution.handlerStarted = true;
                execution.state = TaskExecutionState.RUNNING;
            }
        }
        if (execution.cancelRequested || execution.state == TaskExecutionState.CANCELLED) {
            return completeCancellation(execution);
        }
        try {
            reporter.running(execution.task, null);
        } catch (Exception exception) {
            if (execution.cancelRequested) {
                return completeCancellation(execution);
            }
            execution.state = TaskExecutionState.REPORT_UNKNOWN;
            return execution.state;
        }
        // A cancel may land during the running-report REST window, before the handler registers
        // its control; never start the stream for an already-cancelled task.
        if (execution.cancelRequested) {
            return completeCancellation(execution);
        }
        final Object result;
        try {
            result = handler.execute(execution.task, progress -> reportProgress(execution, progress));
        } catch (TaskCancelledException exception) {
            return completeCancellation(execution);
        } catch (Exception exception) {
            if (execution.cancelRequested) {
                return completeCancellation(execution);
            }
            return completeFailure(execution, exception.getMessage());
        }
        if (execution.cancelRequested) {
            return completeCancellation(execution);
        }
        return completeSuccess(execution, result);
    }

    void registerControl(CollaborationPlugin.TaskDispatch task, TaskControl control) {
        Execution execution = executions.get(keyOf(task));
        if (execution == null || execution.task != task) {
            control.invalidate();
            return;
        }
        boolean cancel;
        boolean pause;
        synchronized (execution) {
            if (execution.isTerminal()) {
                control.invalidate();
                return;
            }
            TaskControl previous = execution.control;
            execution.control = control;
            if (previous != null && previous != control) {
                previous.invalidate();
            }
            cancel = execution.cancelRequested;
            pause = execution.pauseRequested;
        }
        try {
            if (cancel) {
                control.cancel();
            } else if (pause) {
                control.pause();
            }
        } catch (Exception exception) {
            execution.state = TaskExecutionState.REPORT_UNKNOWN;
        }
    }

    void unregisterControl(CollaborationPlugin.TaskDispatch task, TaskControl control) {
        Execution execution = executions.get(keyOf(task));
        if (execution != null) {
            synchronized (execution) {
                if (execution.control == control) {
                    execution.control = null;
                }
            }
        }
        control.invalidate();
    }

    public TaskExecutionState stateOf(CollaborationPlugin.TaskDispatch task) {
        Execution execution = executions.get(keyOf(task));
        return execution == null ? null : execution.state;
    }

    boolean cancel(CollaborationPlugin.TaskCancel cancel) {
        if (cancel.taskId == null || cancel.taskId.isBlank()) {
            return false;
        }
        String key = "task:" + cancel.taskId;
        Execution execution = executions.get(key);
        if (execution == null) {
            pendingCancels.put(key, cancel);
            execution = executions.get(key);
            if (execution == null) {
                return true;
            }
        }
        pendingCancels.remove(key);
        synchronized (execution) {
            if (cancel.version > 0 && execution.task.version > 0
                    && cancel.version < execution.task.version) {
                return false;
            }
            if (cancel.cancelRequestId != null && cancel.cancelRequestId.equals(execution.cancelRequestId)) {
                return true;
            }
            if (execution.state == TaskExecutionState.CANCELLED) {
                return true;
            }
            if (cancel.version > execution.task.version) {
                execution.task.version = cancel.version;
            }
            if (execution.isTerminal()) {
                return false;
            }
            execution.cancelRequested = true;
            execution.cancelRequestId = cancel.cancelRequestId;
        }
        Future<?> future = execution.future;
        TaskControl control = execution.control;
        if (!execution.handlerStarted) {
            if (future != null) {
                future.cancel(false);
            }
            completeCancellation(execution);
            return true;
        }
        try {
            if (control != null) {
                control.cancel();
            }
        } catch (Exception exception) {
            execution.state = TaskExecutionState.REPORT_UNKNOWN;
            return true;
        } finally {
            if (future != null) {
                future.cancel(true);
            }
        }
        completeCancellation(execution);
        return true;
    }

    void pauseAll() {
        for (Execution execution : new ArrayList<>(executions.values())) {
            TaskControl control = execution.control;
            if (control == null || execution.state != TaskExecutionState.RUNNING) {
                continue;
            }
            try {
                control.pause();
                synchronized (execution) {
                    execution.pauseRequested = true;
                    if (!execution.isTerminal()) {
                        execution.state = TaskExecutionState.PAUSED;
                    }
                }
            } catch (Exception exception) {
                execution.state = TaskExecutionState.REPORT_UNKNOWN;
            }
        }
    }

    void resumeAll() {
        for (Execution execution : new ArrayList<>(executions.values())) {
            TaskControl control = execution.control;
            if (control == null || execution.state != TaskExecutionState.PAUSED || execution.cancelRequested) {
                continue;
            }
            try {
                control.resume();
                synchronized (execution) {
                    execution.pauseRequested = false;
                    execution.state = TaskExecutionState.RUNNING;
                }
            } catch (Exception exception) {
                execution.state = TaskExecutionState.REPORT_UNKNOWN;
            }
        }
    }

    void cancelAll(String requestPrefix) {
        for (Execution execution : new ArrayList<>(executions.values())) {
            if (!execution.isTerminal() && !"schedule".equals(execution.task.taskType)) {
                CollaborationPlugin.TaskCancel cancel = new CollaborationPlugin.TaskCancel();
                cancel.taskId = execution.task.taskId;
                cancel.version = execution.task.version;
                cancel.cancelRequestId = requestPrefix + ":" + execution.key;
                cancel(cancel);
            }
        }
    }

    void flushUnknownReports() {
        for (Execution execution : new ArrayList<>(executions.values())) {
            if (execution.state != TaskExecutionState.REPORT_UNKNOWN || execution.pendingStatus == null) {
                continue;
            }
            switch (execution.pendingStatus) {
                case "success" -> completeSuccess(execution, execution.pendingResult);
                case "failed" -> completeFailure(execution, execution.pendingError);
                case "cancelled" -> completeCancellation(execution);
                default -> { }
            }
        }
    }

    public void reconcile(JsonNode snapshot) {
        String taskId = firstText(snapshot, "task_id", "id");
        String runRecordId = snapshot.path("run_record_id").asText("");
        String key = !runRecordId.isBlank() ? "schedule:" + runRecordId
                : taskId.isBlank() ? null : "task:" + taskId;
        if (key == null) {
            return;
        }
        TaskExecutionState platformState = switch (snapshot.path("status").asText("")) {
            case "success" -> TaskExecutionState.SUCCESS;
            case "failed", "offline", "timeout" -> TaskExecutionState.FAILED;
            case "cancelled" -> TaskExecutionState.CANCELLED;
            case "dispatched", "acknowledged", "running", "pending" -> TaskExecutionState.REPORT_UNKNOWN;
            default -> null;
        };
        if (platformState == null) {
            return;
        }
        Execution execution = executions.get(key);
        if (execution == null) {
            execution = new Execution(key, snapshotTask(snapshot, taskId, runRecordId));
            Execution previous = executions.putIfAbsent(key, execution);
            if (previous != null) {
                execution = previous;
            }
        }
        synchronized (execution) {
            long snapshotVersion = snapshot.path("version").asLong(0);
            if (snapshotVersion > 0 && execution.task.version > snapshotVersion) {
                return;
            }
            execution.state = platformState;
            if (platformState == TaskExecutionState.SUCCESS || platformState == TaskExecutionState.FAILED
                    || platformState == TaskExecutionState.CANCELLED) {
                execution.pendingStatus = null;
                execution.pendingResult = null;
                execution.pendingError = null;
            }
        }
    }

    int registrySize() {
        return executions.size();
    }

    private void reportProgress(Execution execution, JsonNode progress) throws Exception {
        if (execution.cancelRequested || execution.state != TaskExecutionState.RUNNING) {
            return;
        }
        reporter.running(execution.task, progress);
    }

    private TaskExecutionState completeSuccess(Execution execution, Object result) {
        synchronized (execution) {
            if (execution.isTerminal() || execution.cancelRequested) {
                return execution.state;
            }
            execution.pendingStatus = "success";
            execution.pendingResult = result;
            execution.pendingError = null;
            try {
                reporter.success(execution.task, result);
                execution.state = TaskExecutionState.SUCCESS;
                execution.pendingStatus = null;
                execution.pendingResult = null;
            } catch (Exception exception) {
                execution.state = TaskExecutionState.REPORT_UNKNOWN;
            }
            return execution.state;
        }
    }

    private TaskExecutionState completeFailure(Execution execution, String error) {
        String message = error == null || error.isBlank() ? "Task execution failed" : error;
        synchronized (execution) {
            if (execution.isTerminal() || execution.cancelRequested) {
                return execution.state;
            }
            execution.pendingStatus = "failed";
            execution.pendingResult = null;
            execution.pendingError = message;
            try {
                reporter.failure(execution.task, message);
                execution.state = TaskExecutionState.FAILED;
                execution.pendingStatus = null;
                execution.pendingError = null;
            } catch (Exception reportError) {
                execution.state = TaskExecutionState.REPORT_UNKNOWN;
            }
            return execution.state;
        }
    }

    private TaskExecutionState completeCancellation(Execution execution) {
        synchronized (execution) {
            if (execution.isTerminal()) {
                return execution.state;
            }
            execution.pendingStatus = "cancelled";
            execution.pendingResult = null;
            execution.pendingError = null;
            try {
                reporter.cancelled(execution.task, execution.cancelRequestId);
                execution.state = TaskExecutionState.CANCELLED;
                execution.pendingStatus = null;
            } catch (Exception exception) {
                execution.state = TaskExecutionState.REPORT_UNKNOWN;
            }
            return execution.state;
        }
    }

    private void evictTerminalIfNecessary() {
        while (executions.size() >= registryCapacity) {
            Execution oldest = executions.values().stream()
                    .filter(Execution::isTerminal)
                    .min(Comparator.comparingLong(value -> value.completedAtEpochMillis))
                    .orElse(null);
            if (oldest == null) {
                return;
            }
            executions.remove(oldest.key, oldest);
        }
    }

    private static CollaborationPlugin.TaskDispatch snapshotTask(JsonNode snapshot, String taskId,
                                                                  String runRecordId) {
        CollaborationPlugin.TaskDispatch task = new CollaborationPlugin.TaskDispatch();
        task.taskId = taskId;
        task.runRecordId = runRecordId;
        task.taskType = runRecordId.isBlank() ? "task" : "schedule";
        task.taskKind = snapshot.path("task_kind").asText("");
        task.version = snapshot.path("version").asLong(0);
        return task;
    }

    private static String firstText(JsonNode node, String first, String second) {
        String value = node.path(first).asText("");
        return value.isBlank() ? node.path(second).asText("") : value;
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

    public static final class Reservation {
        private final Execution execution;
        private final boolean accepted;

        private Reservation(Execution execution, boolean accepted) {
            this.execution = execution;
            this.accepted = accepted;
        }

        boolean accepted() {
            return accepted;
        }
    }

    private static final class Execution {
        private final String key;
        private final CollaborationPlugin.TaskDispatch task;
        private final long completedAtEpochMillis = System.currentTimeMillis();
        private volatile TaskExecutionState state = TaskExecutionState.QUEUED;
        private volatile Future<?> future;
        private volatile TaskControl control;
        private volatile boolean handlerStarted;
        private volatile boolean cancelRequested;
        private volatile boolean pauseRequested;
        private volatile String cancelRequestId;
        private volatile String pendingStatus;
        private volatile Object pendingResult;
        private volatile String pendingError;
        private boolean accepted;

        private Execution(String key, CollaborationPlugin.TaskDispatch task) {
            this.key = key;
            this.task = task;
        }

        private boolean isTerminal() {
            return state == TaskExecutionState.SUCCESS || state == TaskExecutionState.FAILED
                    || state == TaskExecutionState.CANCELLED;
        }
    }
}
