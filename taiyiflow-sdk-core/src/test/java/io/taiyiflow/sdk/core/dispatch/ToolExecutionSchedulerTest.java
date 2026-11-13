package io.taiyiflow.sdk.core.dispatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionSchedulerTest {

    private final List<ToolExecutionScheduler> schedulers = new ArrayList<>();

    @AfterEach
    void closeSchedulers() {
        schedulers.forEach(ToolExecutionScheduler::close);
    }

    @Test
    void globalLimitIsSharedAndEligibleWaitersRunInFifoOrder() throws Exception {
        ToolExecutionScheduler scheduler = scheduler(1, 1, 4, Duration.ofSeconds(5));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();

        CompletableFuture<String> first = scheduler.submit(context("flow-a", "session-a"), () -> {
            enter(active, maximum, order, "first");
            firstStarted.countDown();
            try {
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
            } finally {
                active.decrementAndGet();
            }
            return "first";
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        CompletableFuture<String> second = scheduler.submit(context("flow-b", "session-b"),
                tracked(active, maximum, order, "second"));
        CompletableFuture<String> third = scheduler.submit(context("flow-a", "session-a"),
                tracked(active, maximum, order, "third"));

        assertEquals(2, scheduler.getQueuedCount());
        releaseFirst.countDown();
        assertEquals("first", first.get(2, TimeUnit.SECONDS));
        assertEquals("second", second.get(2, TimeUnit.SECONDS));
        assertEquals("third", third.get(2, TimeUnit.SECONDS));
        assertEquals(List.of("first", "second", "third"), order);
        assertEquals(1, maximum.get());
        assertEquals(0, scheduler.getRunningCount());
        assertEquals(0, scheduler.getQueuedCount());
    }

    @Test
    void perSessionLimitDoesNotBlockAnotherSession() throws Exception {
        ToolExecutionScheduler scheduler = scheduler(2, 1, 4, Duration.ofSeconds(5));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger sameSessionExecutions = new AtomicInteger();

        CompletableFuture<String> first = scheduler.submit(context("flow", "session-a"), () -> {
            sameSessionExecutions.incrementAndGet();
            firstStarted.countDown();
            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
            return "first";
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        CompletableFuture<String> queued = scheduler.submit(context("flow", "session-a"), () -> {
            sameSessionExecutions.incrementAndGet();
            return "queued";
        });
        CompletableFuture<String> otherSession = scheduler.submit(context("flow", "session-b"),
                () -> "other");

        assertEquals("other", otherSession.get(1, TimeUnit.SECONDS));
        assertEquals(1, sameSessionExecutions.get());
        assertEquals(1, scheduler.getSessionRunningCount("session-a"));
        releaseFirst.countDown();
        assertEquals("first", first.get(2, TimeUnit.SECONDS));
        assertEquals("queued", queued.get(2, TimeUnit.SECONDS));
        assertEquals(2, sameSessionExecutions.get());
    }

    @Test
    void queueFullIsAnExplicitFailure() throws Exception {
        ToolExecutionScheduler scheduler = scheduler(1, 1, 1, Duration.ofSeconds(5));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rejectedExecutions = new AtomicInteger();
        CompletableFuture<String> first = scheduler.submit(context("flow", "first"), () -> {
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return "first";
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        CompletableFuture<String> queued = scheduler.submit(context("flow", "queued"), () -> "queued");
        CompletableFuture<String> rejected = scheduler.submit(context("flow", "rejected"), () -> {
            rejectedExecutions.incrementAndGet();
            return "rejected";
        });

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> rejected.get(1, TimeUnit.SECONDS));
        ToolExecutionException error = (ToolExecutionException) failure.getCause();
        assertEquals(ToolExecutionException.Reason.QUEUE_FULL, error.getReason());
        assertTrue(error.getMessage().contains("queue is full"));
        assertEquals(0, rejectedExecutions.get());

        release.countDown();
        assertEquals("first", first.get(2, TimeUnit.SECONDS));
        assertEquals("queued", queued.get(2, TimeUnit.SECONDS));
    }

    @Test
    void queuedCancellationRemovesWaiterAndAllowsFollowingWork() throws Exception {
        ToolExecutionScheduler scheduler = scheduler(1, 1, 4, Duration.ofSeconds(5));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger cancelledExecutions = new AtomicInteger();
        ToolExecutionContext.CancellationSource cancellation =
                new ToolExecutionContext.CancellationSource();

        CompletableFuture<String> first = scheduler.submit(context("flow", "first"), () -> {
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return "first";
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        CompletableFuture<String> cancelled = scheduler.submit(
                ToolExecutionContext.builder("flow", "cancelled").cancellation(cancellation).build(),
                () -> {
                    cancelledExecutions.incrementAndGet();
                    return "cancelled";
                });
        CompletableFuture<String> following = scheduler.submit(context("flow", "following"),
                () -> "following");
        assertEquals(2, scheduler.getQueuedCount());

        assertTrue(cancellation.cancel());
        assertTrue(cancelled.isCancelled());
        assertEquals(1, scheduler.getQueuedCount());
        assertThrows(CancellationException.class, cancelled::join);

        release.countDown();
        assertEquals("first", first.get(2, TimeUnit.SECONDS));
        assertEquals("following", following.get(2, TimeUnit.SECONDS));
        assertEquals(0, cancelledExecutions.get());
    }

    @Test
    void runningCancellationInterruptsAndReleasesPermitInFinally() throws Exception {
        ToolExecutionScheduler scheduler = scheduler(1, 1, 4, Duration.ofSeconds(5));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        ToolExecutionContext.CancellationSource cancellation =
                new ToolExecutionContext.CancellationSource();
        ToolExecutionContext firstContext = ToolExecutionContext.builder("flow", "first")
                .cancellation(cancellation).build();

        CompletableFuture<String> first = scheduler.submit(firstContext, () -> {
            started.countDown();
            try {
                Thread.sleep(5000);
                return "unexpected";
            } finally {
                finished.countDown();
            }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        CompletableFuture<String> following = scheduler.submit(context("flow", "following"),
                () -> "following");

        assertTrue(cancellation.cancel());
        assertTrue(first.isCancelled());
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertEquals("following", following.get(2, TimeUnit.SECONDS));
        assertEquals(0, scheduler.getRunningCount());
    }

    @Test
    void executionTimeoutReturnsErrorAndDoesNotLeakPermit() throws Exception {
        ToolExecutionScheduler scheduler = scheduler(1, 1, 2, Duration.ofMillis(40));
        ToolExecutionException error = assertThrows(ToolExecutionException.class,
                () -> scheduler.execute(context("flow", "slow"), () -> {
                    Thread.sleep(5000);
                    return "unexpected";
                }));

        assertEquals(ToolExecutionException.Reason.EXECUTION_TIMEOUT, error.getReason());
        assertTrue(error.getMessage().contains("timed out"));
        assertEquals("next", scheduler.execute(context("flow", "next"), () -> "next"));
        assertEquals(0, scheduler.getRunningCount());
    }

    @Test
    void closeCancelsQueueInterruptsRunningAndStopsTimeoutThread() throws Exception {
        ToolExecutionScheduler scheduler = scheduler(1, 1, 2, Duration.ofSeconds(5));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        CompletableFuture<String> running = scheduler.submit(context("flow", "running"), () -> {
            started.countDown();
            try {
                Thread.sleep(5000);
                return "unexpected";
            } finally {
                finished.countDown();
            }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        CompletableFuture<String> queued = scheduler.submit(context("flow", "queued"), () -> "queued");

        scheduler.close();

        assertTrue(scheduler.isClosed());
        assertEquals(ToolExecutionException.Reason.CLOSED, failureReason(running));
        assertEquals(ToolExecutionException.Reason.CLOSED, failureReason(queued));
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertEquals(0, scheduler.getQueuedCount());
        assertEquals(0, scheduler.getRunningCount());
        assertEquals(ToolExecutionException.Reason.CLOSED, failureReason(
                scheduler.submit(context("flow", "late"), () -> "late")));
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> "taiyiflow-tool-timeouts".equals(thread.getName()))
                .noneMatch(Thread::isAlive));
    }

    @Test
    void invalidConfigurationIsRejectedInsteadOfBecomingUnbounded() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolRuntime.Config(0, 1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolRuntime.Config(1, 0, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolRuntime.Config(1, 1, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolRuntime.Config(1, 1, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolRuntime.Config(1, 1, 1, null));
    }

    private ToolExecutionScheduler scheduler(int global, int perSession, int queue, Duration timeout) {
        ToolExecutionScheduler scheduler = new ToolExecutionScheduler(global, perSession, queue, timeout);
        schedulers.add(scheduler);
        return scheduler;
    }

    private static ToolExecutionContext context(String flowId, String sessionId) {
        return ToolExecutionContext.builder(flowId, sessionId).build();
    }

    private static java.util.concurrent.Callable<String> tracked(
            AtomicInteger active, AtomicInteger maximum, List<String> order, String value) {
        return () -> {
            enter(active, maximum, order, value);
            try {
                return value;
            } finally {
                active.decrementAndGet();
            }
        };
    }

    private static void enter(AtomicInteger active, AtomicInteger maximum,
                              List<String> order, String value) {
        int current = active.incrementAndGet();
        maximum.accumulateAndGet(current, Math::max);
        order.add(value);
    }

    private static ToolExecutionException.Reason failureReason(CompletableFuture<?> future)
            throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
        assertInstanceOf(ToolExecutionException.class, failure.getCause());
        return ((ToolExecutionException) failure.getCause()).getReason();
    }
}
