package io.taiyiflow.sdk.worker;

import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.collaboration.CollaborationPlugin;
import io.taiyiflow.sdk.core.report.FileReportOutbox;
import io.taiyiflow.sdk.worker.auth.WorkerAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigitalWorkerTest {

    @Test
    void requiresPlatformIdentityValidator(@TempDir Path workspace) {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> baseBuilder(workspace).build());

        assertTrue(error.getMessage().contains("identityValidator"));
    }

    @Test
    void identityRejectionStopsBeforeOpeningCollaboration(@TempDir Path workspace) throws Exception {
        AtomicBoolean validated = new AtomicBoolean();
        DigitalWorker worker = baseBuilder(workspace)
                .identityValidator((identity, role, abilities) -> {
                    validated.set(true);
                    throw new IllegalStateException("worker is not registered");
                })
                .build();

        assertThrows(IllegalStateException.class, worker::start);

        assertTrue(validated.get());
        assertEquals(WorkerState.OFFLINE, worker.getState());
        worker.await();
    }

    @Test
    void registrarSuppliesIdentityButCannotBypassValidation(@TempDir Path workspace) throws Exception {
        AtomicReference<WorkerIdentity> validated = new AtomicReference<>();
        DigitalWorker worker = DigitalWorker.builder()
                .baseUrl("http://127.0.0.1:1")
                .auth(WorkerAuth.apiKey("test-key"))
                .displayName("Registered Worker")
                .workspace(workspace)
                .workerRegistrar(request -> WorkerRegistrationResult.of(
                        WorkerIdentity.of("registered-worker", request.displayName())))
                .identityValidator((identity, role, abilities) -> {
                    validated.set(identity);
                    throw new IllegalStateException("registration rejected");
                })
                .build();

        assertThrows(IllegalStateException.class, worker::start);

        assertEquals("registered-worker", validated.get().workerId());
        assertEquals(WorkerState.OFFLINE, worker.getState());
    }

    @Test
    void configuredIdentityDoesNotInvokeRegistrar(@TempDir Path workspace) throws Exception {
        AtomicBoolean registered = new AtomicBoolean();
        DigitalWorker worker = baseBuilder(workspace)
                .workerRegistrar(request -> {
                    registered.set(true);
                    return WorkerRegistrationResult.of(WorkerIdentity.of("other", "Other"));
                })
                .identityValidator((identity, role, abilities) -> {
                    throw new IllegalStateException("stop before connection");
                })
                .build();

        assertThrows(IllegalStateException.class, worker::start);

        assertEquals(false, registered.get());
    }

    @Test
    void defaultsToPlatformApprovalAndAutoRecovery(@TempDir Path workspace) throws Exception {
        DigitalWorker worker = baseBuilder(workspace)
                .identityValidator((identity, role, abilities) -> { })
                .build();

        assertEquals(ApprovalChannel.PLATFORM, worker.getApprovalChannel());
        assertEquals(io.taiyiflow.sdk.RecoveryPolicy.AUTO, worker.getRecoveryPolicy());
        worker.stop();
    }

    @Test
    void allFlowAgentsShareWorkerToolRuntime(@TempDir Path workspace) throws Exception {
        DigitalWorker worker = baseBuilder(workspace)
                .identityValidator((identity, role, abilities) -> { })
                .build();
        var newAgent = DigitalWorker.class.getDeclaredMethod("newAgent", String.class);
        newAgent.setAccessible(true);
        TaiyiAgent first = (TaiyiAgent) newAgent.invoke(worker, "flow-1");
        TaiyiAgent second = (TaiyiAgent) newAgent.invoke(worker, "flow-2");
        try {
            assertSame(worker.toolRuntime(), first.getToolRuntime());
            assertSame(first.getToolRuntime(), second.getToolRuntime());
        } finally {
            first.shutdown();
            second.shutdown();
            worker.stop();
        }
        assertTrue(worker.toolRuntime().isClosed());
    }

    @Test
    void stopCleansUpAndInterruptsTasksAfterDrainTimeout(@TempDir Path workspace) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean stopHookCalled = new AtomicBoolean();
        DigitalWorker worker = baseBuilder(workspace)
                .identityValidator((identity, role, abilities) -> { })
                .drainTimeout(Duration.ofMillis(20))
                .onTask((task, progress) -> {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return null;
                })
                .onWorker(new WorkerHook() {
                    @Override
                    public void onStop() {
                        stopHookCalled.set(true);
                    }
                })
                .build();
        var runtimeField = DigitalWorker.class.getDeclaredField("runtime");
        runtimeField.setAccessible(true);
        WorkerRuntime runtime = (WorkerRuntime) runtimeField.get(worker);
        runtime.online();
        CollaborationPlugin.TaskDispatch task = new CollaborationPlugin.TaskDispatch();
        task.taskId = "task-1";
        task.taskType = "primary";
        var dispatch = DigitalWorker.class.getDeclaredMethod("dispatch", CollaborationPlugin.TaskDispatch.class);
        dispatch.setAccessible(true);
        assertTrue((Boolean) dispatch.invoke(worker, task));
        assertTrue(started.await(1, TimeUnit.SECONDS));

        assertThrows(IllegalStateException.class, worker::stop);

        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertTrue(stopHookCalled.get());
        assertEquals(WorkerState.OFFLINE, worker.getState());
        worker.stop();
    }

    @Test
    void stateDirectoryIsSharedByAllFlowAgents(@TempDir Path workspace) throws Exception {
        DigitalWorker worker = baseBuilder(workspace)
                .stateDir(workspace.resolve("state"))
                .identityValidator((identity, role, abilities) -> { })
                .build();
        var newAgent = DigitalWorker.class.getDeclaredMethod("newAgent", String.class);
        newAgent.setAccessible(true);
        TaiyiAgent first = (TaiyiAgent) newAgent.invoke(worker, "flow-1");
        TaiyiAgent second = (TaiyiAgent) newAgent.invoke(worker, "flow-2");
        try {
            assertTrue(worker.reportOutbox() instanceof FileReportOutbox);
            assertSame(worker.reportOutbox(), first.getToolRuntime().getReportOutbox());
            assertSame(worker.reportOutbox(), second.getToolRuntime().getReportOutbox());
        } finally {
            first.shutdown();
            second.shutdown();
            worker.stop();
        }
    }

    @Test
    void duplicateDispatchIsNotAcknowledgedWhileFirstTaskIsRunning(@TempDir Path workspace) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        DigitalWorker worker = baseBuilder(workspace)
                .identityValidator((identity, role, abilities) -> { })
                .taskReporter(new TaskReporter() {
                    @Override
                    public void running(CollaborationPlugin.TaskDispatch task,
                                        com.fasterxml.jackson.databind.JsonNode progress) {
                    }

                    @Override
                    public void success(CollaborationPlugin.TaskDispatch task, Object result) {
                    }

                    @Override
                    public void failure(CollaborationPlugin.TaskDispatch task, String error) {
                    }
                })
                .onTask((task, progress) -> {
                    executions.incrementAndGet();
                    started.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    return "done";
                })
                .build();
        var runtimeField = DigitalWorker.class.getDeclaredField("runtime");
        runtimeField.setAccessible(true);
        WorkerRuntime runtime = (WorkerRuntime) runtimeField.get(worker);
        runtime.online();
        CollaborationPlugin.TaskDispatch task = new CollaborationPlugin.TaskDispatch();
        task.taskId = "task-1";
        task.taskType = "task";
        var dispatch = DigitalWorker.class.getDeclaredMethod("dispatch", CollaborationPlugin.TaskDispatch.class);
        dispatch.setAccessible(true);
        try {
            assertTrue((Boolean) dispatch.invoke(worker, task));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(false, dispatch.invoke(worker, task));
            assertEquals(1, executions.get());
        } finally {
            release.countDown();
            worker.stop();
        }
    }

    private static DigitalWorker.Builder baseBuilder(Path workspace) {
        return DigitalWorker.builder()
                .baseUrl("http://127.0.0.1:1")
                .auth(WorkerAuth.apiKey("test-key"))
                .identity(WorkerIdentity.of("worker-1", "Test Worker"))
                .workspace(workspace);
    }
}
