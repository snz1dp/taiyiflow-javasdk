package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.collaboration.CollaborationPlugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void queuedCancellationSkipsHandlerAndBindsLateFuture() {
        RecordingReporter reporter = new RecordingReporter();
        AtomicInteger executions = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor((task, progress) -> {
            executions.incrementAndGet();
            return "done";
        }, reporter);
        CollaborationPlugin.TaskDispatch task = task("task-1");
        TaskExecutor.Reservation reservation = executor.prepare(task);
        FutureTask<Void> future = new FutureTask<>(() -> null);
        executor.attachFuture(reservation, future);

        assertTrue(executor.cancel(cancel(task, 1, "cancel-1")));
        assertTrue(future.isCancelled());
        assertEquals(TaskExecutionState.CANCELLED, executor.executePrepared(reservation));
        assertEquals(0, executions.get());
        assertEquals(List.of("cancelled:cancel-1"), reporter.events);
    }

    @Test
    void staleAndDuplicateCancellationCannotAffectNewExecution() throws Exception {
        RecordingReporter reporter = new RecordingReporter();
        AtomicInteger executions = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor((task, progress) -> {
            executions.incrementAndGet();
            return "done";
        }, reporter);
        CollaborationPlugin.TaskDispatch initial = task("task-1");
        assertEquals(TaskExecutionState.SUCCESS, executor.execute(initial));

        CollaborationPlugin.TaskDispatch replacement = task("task-1");
        replacement.version = 2;
        TaskExecutor.Reservation reservation = executor.prepare(replacement);
        assertTrue(reservation.accepted());
        assertFalse(executor.cancel(cancel(replacement, 1, "old-cancel")));
        assertEquals(TaskExecutionState.SUCCESS, executor.executePrepared(reservation));
        assertEquals(2, executions.get());

        CollaborationPlugin.TaskDispatch queued = task("task-2");
        queued.version = 2;
        TaskExecutor.Reservation queuedReservation = executor.prepare(queued);
        assertTrue(executor.cancel(cancel(queued, 2, "cancel-2")));
        assertTrue(executor.cancel(cancel(queued, 2, "cancel-2")));
        assertEquals(1, reporter.events.stream().filter("cancelled:cancel-2"::equals).count());
        assertEquals(TaskExecutionState.CANCELLED, executor.executePrepared(queuedReservation));
    }

    @Test
    void pauseResumeAndCancelUseRegisteredExecutionControl() throws Exception {
        RecordingReporter reporter = new RecordingReporter();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskExecutor executor = new TaskExecutor((task, progress) -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return "done";
        }, reporter);
        CollaborationPlugin.TaskDispatch task = task("task-1");
        TaskExecutor.Reservation reservation = executor.prepare(task);
        FutureTask<TaskExecutionState> running = new FutureTask<>(() -> executor.executePrepared(reservation));
        Thread thread = new Thread(running);
        thread.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        AtomicInteger pauses = new AtomicInteger();
        AtomicInteger resumes = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        TaskControl control = new TaskControl() {
            @Override
            public void pause() {
                pauses.incrementAndGet();
            }

            @Override
            public void resume() {
                resumes.incrementAndGet();
            }

            @Override
            public void cancel() {
                cancels.incrementAndGet();
            }

            @Override
            public void invalidate() {
            }
        };
        executor.registerControl(task, control);

        executor.pauseAll();
        assertEquals(TaskExecutionState.PAUSED, executor.stateOf(task));
        executor.resumeAll();
        assertEquals(TaskExecutionState.RUNNING, executor.stateOf(task));
        assertTrue(executor.cancel(cancel(task, 1, "cancel-1")));
        release.countDown();
        executor.unregisterControl(task, control);

        assertEquals(TaskExecutionState.CANCELLED, running.get(1, TimeUnit.SECONDS));
        assertEquals(1, pauses.get());
        assertEquals(1, resumes.get());
        assertEquals(1, cancels.get());
    }

    @Test
    void unknownResultIsOnlyReportedAgainWithoutHandlerReplay() {
        RecordingReporter reporter = new RecordingReporter();
        reporter.failOn = "success";
        reporter.failOnce = true;
        AtomicInteger executions = new AtomicInteger();
        TaskExecutor executor = new TaskExecutor((task, progress) -> {
            executions.incrementAndGet();
            return "done";
        }, reporter);
        CollaborationPlugin.TaskDispatch task = task("task-1");

        assertEquals(TaskExecutionState.REPORT_UNKNOWN, executor.executePrepared(executor.prepare(task)));
        assertFalse(executor.prepare(task).accepted());
        reporter.failOn = "";
        executor.flushUnknownReports();

        assertEquals(TaskExecutionState.SUCCESS, executor.stateOf(task));
        assertEquals(1, executions.get());
        assertEquals(List.of("running", "success:done"), reporter.events);
    }

    @Test
    void boundedRegistryRejectsNewWorkButReclaimsTerminalTombstones() throws Exception {
        RecordingReporter reporter = new RecordingReporter();
        TaskExecutor executor = new TaskExecutor((task, progress) -> "done", reporter, 1);
        CollaborationPlugin.TaskDispatch first = task("task-1");
        TaskExecutor.Reservation reservation = executor.prepare(first);

        assertTrue(reservation.accepted());
        assertFalse(executor.prepare(first).accepted());
        assertFalse(executor.prepare(task("task-2")).accepted());
        assertEquals(TaskExecutionState.SUCCESS, executor.executePrepared(reservation));
        assertTrue(executor.prepare(task("task-2")).accepted());
    }

    private static CollaborationPlugin.TaskCancel cancel(
            CollaborationPlugin.TaskDispatch task, long version, String requestId) {
        CollaborationPlugin.TaskCancel cancel = new CollaborationPlugin.TaskCancel();
        cancel.taskId = task.taskId;
        cancel.taskKind = task.taskKind;
        cancel.version = version;
        cancel.cancelRequestId = requestId;
        return cancel;
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
        private boolean failOnce;

        @Override
        public void running(CollaborationPlugin.TaskDispatch task, JsonNode progress) throws Exception {
            if (shouldFail("running")) {
                throw new Exception("report unavailable");
            }
            events.add(progress == null ? "running" : "running:" + progress.path("percent").asInt());
        }

        @Override
        public void success(CollaborationPlugin.TaskDispatch task, Object result) throws Exception {
            if (shouldFail("success")) {
                throw new Exception("report unavailable");
            }
            Object summary = result instanceof Map<?, ?> map ? map.get("summary") : result;
            events.add("success:" + summary);
        }

        @Override
        public void failure(CollaborationPlugin.TaskDispatch task, String error) throws Exception {
            if (shouldFail("failure")) {
                throw new Exception("report unavailable");
            }
            events.add("failure:" + error);
        }

        @Override
        public void cancelled(CollaborationPlugin.TaskDispatch task, String cancelRequestId) {
            events.add("cancelled:" + cancelRequestId);
        }

        private boolean shouldFail(String event) {
            if (!event.equals(failOn)) {
                return false;
            }
            if (failOnce) {
                failOn = "";
            }
            return true;
        }
    }
}
