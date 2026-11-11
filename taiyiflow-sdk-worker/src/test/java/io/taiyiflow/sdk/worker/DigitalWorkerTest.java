package io.taiyiflow.sdk.worker;

import io.taiyiflow.sdk.collaboration.CollaborationPlugin;
import io.taiyiflow.sdk.worker.auth.WorkerAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void defaultsToPlatformApprovalAndAutoRecovery(@TempDir Path workspace) throws Exception {
        DigitalWorker worker = baseBuilder(workspace)
                .identityValidator((identity, role, abilities) -> { })
                .build();

        assertEquals(ApprovalChannel.PLATFORM, worker.getApprovalChannel());
        assertEquals(io.taiyiflow.sdk.RecoveryPolicy.AUTO, worker.getRecoveryPolicy());
        worker.stop();
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

    private static DigitalWorker.Builder baseBuilder(Path workspace) {
        return DigitalWorker.builder()
                .baseUrl("http://127.0.0.1:1")
                .auth(WorkerAuth.apiKey("test-key"))
                .identity(WorkerIdentity.of("worker-1", "Test Worker"))
                .workspace(workspace);
    }
}
