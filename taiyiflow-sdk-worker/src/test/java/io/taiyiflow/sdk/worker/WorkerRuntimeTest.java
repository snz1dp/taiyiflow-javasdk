package io.taiyiflow.sdk.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerRuntimeTest {

    @Test
    void pauseRejectsNewWorkAndDrainWaitsForInflightTasks() throws Exception {
        WorkerRuntime runtime = new WorkerRuntime(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime.online();
        assertTrue(runtime.submit(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertEquals(WorkerState.BUSY, runtime.state());
        runtime.pause();

        ExecutorService waiter = Executors.newSingleThreadExecutor();
        Future<Boolean> drained = waiter.submit(() -> runtime.drain(Duration.ofSeconds(2)));
        try {
            assertFalse(runtime.submit(() -> { }));
            assertEquals(WorkerState.PAUSED, runtime.state());
            release.countDown();
            assertTrue(drained.get(1, TimeUnit.SECONDS));
            assertEquals(0, runtime.activeTasks());
        } finally {
            release.countDown();
            waiter.shutdownNow();
            runtime.offline();
        }
    }

    @Test
    void concurrencyLimitIncludesQueuedTasksInDrain() throws Exception {
        WorkerRuntime runtime = new WorkerRuntime(2);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        runtime.online();
        for (int i = 0; i < 4; i++) {
            assertTrue(runtime.submit(() -> {
                int current = running.incrementAndGet();
                maximum.accumulateAndGet(current, Math::max);
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    running.decrementAndGet();
                }
            }));
        }
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertEquals(4, runtime.activeTasks());
        release.countDown();

        assertTrue(runtime.drain(Duration.ofSeconds(2)));
        assertEquals(2, maximum.get());
        runtime.offline();
    }

    @Test
    void queuedCancellationPreventsExecutionAndReleasesDrain() throws Exception {
        WorkerRuntime runtime = new WorkerRuntime(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        runtime.online();
        assertTrue(runtime.submit(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(started.await(1, TimeUnit.SECONDS));
        WorkerRuntime.Submission queued = runtime.submitWithHandle(executions::incrementAndGet);

        assertTrue(queued.accepted());
        assertTrue(queued.future().cancel(false));
        assertTrue(queued.future().isCancelled());
        assertEquals(1, runtime.activeTasks());
        release.countDown();

        assertTrue(runtime.drain(Duration.ofSeconds(1)));
        assertEquals(0, executions.get());
        assertEquals(0, runtime.activeTasks());
        runtime.offline();
    }

    @Test
    void runningCancellationWaitsForTaskUnwindBeforeDrainCompletes() throws Exception {
        WorkerRuntime runtime = new WorkerRuntime(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime.online();
        WorkerRuntime.Submission running = runtime.submitWithHandle(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        assertTrue(running.future().cancel(true));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertEquals(1, runtime.activeTasks());
        release.countDown();

        assertTrue(runtime.drain(Duration.ofSeconds(1)));
        assertEquals(0, runtime.activeTasks());
        runtime.offline();
    }

    @Test
    void queueCapacityRejectsBeforeUnboundedWorkAccumulates() throws Exception {
        WorkerRuntime runtime = new WorkerRuntime(1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime.online();
        assertTrue(runtime.submit(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTrue(runtime.submit(() -> { }));

        WorkerRuntime.Submission rejected = runtime.submitWithHandle(() -> { });

        assertFalse(rejected.accepted());
        assertTrue(rejected.future().isDone());
        assertEquals(2, runtime.activeTasks());
        release.countDown();
        assertTrue(runtime.drain(Duration.ofSeconds(1)));
        runtime.offline();
    }

    @Test
    void offlineSubtractsDiscardedQueuedTasksFromActiveCount() throws Exception {
        WorkerRuntime runtime = new WorkerRuntime(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        List<WorkerRuntime.Submission> submissions = new ArrayList<>();
        runtime.online();
        for (int i = 0; i < 3; i++) {
            submissions.add(runtime.submitWithHandle(() -> {
                executions.incrementAndGet();
                started.countDown();
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted.countDown();
                }
            }));
            assertTrue(submissions.get(i).accepted());
        }
        assertTrue(started.await(1, TimeUnit.SECONDS));

        runtime.offline();

        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertTrue(runtime.drain(Duration.ofSeconds(1)));
        assertEquals(0, runtime.activeTasks());
        assertEquals(1, executions.get());
        assertTrue(submissions.get(1).future().isCancelled());
        assertTrue(submissions.get(2).future().isCancelled());
    }

    @Test
    void offlineIsTerminalAndCannotBeRevivedByLateReady() throws Exception {
        WorkerRuntime runtime = new WorkerRuntime(1);
        runtime.online();
        runtime.offline();

        runtime.online();

        assertEquals(WorkerState.OFFLINE, runtime.state());
        assertFalse(runtime.submit(() -> { }));
        runtime.await();
    }
}
