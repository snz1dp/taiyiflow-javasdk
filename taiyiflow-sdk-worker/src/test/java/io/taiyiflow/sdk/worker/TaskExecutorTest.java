package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.collaboration.CollaborationPlugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskExecutorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void executesOnceAndConfirmsProgressAndResult() throws Exception {
        RecordingReporter reporter = new RecordingReporter();
        AtomicInteger executions = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor((task, progress) -> {
            executions.incrementAndGet();
            progress.report(MAPPER.createObjectNode().put("percent", 50));
            return Map.of("summary", "done");
        }, reporter);
        CollaborationPlugin.TaskDispatch task = task("task-1");

        assertEquals(TaskExecutionState.SUCCESS, executor.execute(task));
        assertEquals(TaskExecutionState.SUCCESS, executor.execute(task));

        assertEquals(1, executions.get());
        assertEquals(List.of("running", "running:50", "success:done"), reporter.events);
        assertEquals(TaskExecutionState.SUCCESS, executor.stateOf(task));
    }

    @Test
    void handlerFailureIsReportedWithoutRerunning() {
        RecordingReporter reporter = new RecordingReporter();
        AtomicInteger executions = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor((task, progress) -> {
            executions.incrementAndGet();
            throw new IllegalStateException("bad input");
        }, reporter);
        CollaborationPlugin.TaskDispatch task = task("task-1");

        assertEquals(TaskExecutionState.FAILED, executor.execute(task));
        assertEquals(TaskExecutionState.FAILED, executor.execute(task));

        assertEquals(1, executions.get());
        assertEquals(List.of("running", "failure:bad input"), reporter.events);
    }

    @Test
    void runningReportFailurePreventsSideEffects() {
        RecordingReporter reporter = new RecordingReporter();
        reporter.failOn = "running";
        AtomicInteger executions = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor((task, progress) -> {
            executions.incrementAndGet();
            return "done";
        }, reporter);

        assertEquals(TaskExecutionState.REPORT_UNKNOWN, executor.execute(task("task-1")));
        assertEquals(0, executions.get());
    }

    @Test
    void resultReportFailureNeverReexecutesTheHandler() {
        RecordingReporter reporter = new RecordingReporter();
        reporter.failOn = "success";
        AtomicInteger executions = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor((task, progress) -> {
            executions.incrementAndGet();
            return "done";
        }, reporter);
        CollaborationPlugin.TaskDispatch task = task("task-1");

        assertEquals(TaskExecutionState.REPORT_UNKNOWN, executor.execute(task));
        assertEquals(TaskExecutionState.REPORT_UNKNOWN, executor.execute(task));
        assertEquals(1, executions.get());
    }

    @Test
    void reconcilesPlatformSnapshotsWithoutReplayingUnknownWork() {
        RecordingReporter reporter = new RecordingReporter();
        AtomicInteger executions = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor((task, progress) -> {
            executions.incrementAndGet();
            return "done";
        }, reporter);
        CollaborationPlugin.TaskDispatch task = task("task-1");

        executor.reconcile(MAPPER.createObjectNode().put("task_id", "task-1").put("status", "running"));

        assertEquals(TaskExecutionState.REPORT_UNKNOWN, executor.execute(task));
        assertEquals(0, executions.get());

        executor.reconcile(MAPPER.createObjectNode().put("task_id", "task-1").put("status", "success"));
        assertEquals(TaskExecutionState.SUCCESS, executor.stateOf(task));
    }

    @Test
    void scheduleRunsUseRunRecordIdentity() {
        RecordingReporter reporter = new RecordingReporter();
        AtomicInteger executions = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor((task, progress) -> {
            executions.incrementAndGet();
            return "done";
        }, reporter);
        CollaborationPlugin.TaskDispatch task = task("");
        task.taskType = "schedule";
        task.runRecordId = "run-record-1";

        assertEquals(TaskExecutionState.SUCCESS, executor.execute(task));
        assertEquals(TaskExecutionState.SUCCESS, executor.execute(task));
        assertEquals(1, executions.get());
    }

    private static CollaborationPlugin.TaskDispatch task(String taskId) {
        CollaborationPlugin.TaskDispatch task = new CollaborationPlugin.TaskDispatch();
        task.taskId = taskId;
        task.taskType = "task";
        task.inputValue = "run";
        return task;
    }

    private static final class RecordingReporter implements TaskReporter {
        private final List<String> events = new ArrayList<>();
        private String failOn = "";

        @Override
        public void running(CollaborationPlugin.TaskDispatch task, JsonNode progress) throws Exception {
            if ("running".equals(failOn)) {
                throw new Exception("report unavailable");
            }
            events.add(progress == null ? "running" : "running:" + progress.path("percent").asInt());
        }

        @Override
        public void success(CollaborationPlugin.TaskDispatch task, Object result) throws Exception {
            if ("success".equals(failOn)) {
                throw new Exception("report unavailable");
            }
            Object summary = result instanceof Map<?, ?> map ? map.get("summary") : result;
            events.add("success:" + summary);
        }

        @Override
        public void failure(CollaborationPlugin.TaskDispatch task, String error) throws Exception {
            if ("failure".equals(failOn)) {
                throw new Exception("report unavailable");
            }
            events.add("failure:" + error);
        }
    }
}
