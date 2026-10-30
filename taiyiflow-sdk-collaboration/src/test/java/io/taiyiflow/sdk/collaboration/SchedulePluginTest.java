package io.taiyiflow.sdk.collaboration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SchedulePluginTest {

    private CollaborationPlugin stubCollab;
    private SchedulePlugin scheduler;
    private ConcurrentLinkedQueue<String[]> sentResults;
    private ConcurrentLinkedQueue<String[]> sentErrors;

    @BeforeEach
    void setUp() {
        sentResults = new ConcurrentLinkedQueue<>();
        sentErrors = new ConcurrentLinkedQueue<>();
        stubCollab = new CollaborationPlugin("http://localhost", "key") {
            @Override
            public void sendTaskResult(String taskId, Object result) {
                sentResults.add(new String[]{taskId, String.valueOf(result)});
            }

            @Override
            public void sendTaskError(String taskId, String error) {
                sentErrors.add(new String[]{taskId, error});
            }
        };
        scheduler = new SchedulePlugin(stubCollab);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void scheduleOnceExecutesAfterDelay() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger(0);

        scheduler.scheduleOnce("task-1", () -> {
            counter.incrementAndGet();
            latch.countDown();
        }, 100);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals(1, counter.get());
        Thread.sleep(200);
        assertEquals(1, sentResults.size());
        assertEquals("task-1", sentResults.peek()[0]);
        assertEquals("completed", sentResults.peek()[1]);
    }

    @Test
    void scheduleOnceErrorReportsFailure() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        scheduler.scheduleOnce("task-err", () -> {
            latch.countDown();
            throw new RuntimeException("task failed");
        }, 50);

        assertTrue(latch.await(3, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(1, sentErrors.size());
        assertEquals("task-err", sentErrors.peek()[0]);
        assertEquals("task failed", sentErrors.peek()[1]);
    }

    @Test
    void recurringTaskExecutesMultipleTimes() throws Exception {
        CountDownLatch latch = new CountDownLatch(3);
        AtomicInteger counter = new AtomicInteger(0);

        scheduler.schedule("recurring-1", () -> {
            counter.incrementAndGet();
            latch.countDown();
        }, 0, 100);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(counter.get() >= 3);
        scheduler.cancel("recurring-1");
    }

    @Test
    void cancelStopsRecurringTask() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);

        scheduler.schedule("cancel-me", counter::incrementAndGet, 0, 100);
        Thread.sleep(250);
        scheduler.cancel("cancel-me");

        int countAtCancel = counter.get();
        Thread.sleep(300);
        assertEquals(countAtCancel, counter.get());
    }

    @Test
    void cancelNonExistentTaskIsNoOp() {
        assertDoesNotThrow(() -> scheduler.cancel("nonexistent"));
    }

    @Test
    void recurringTaskErrorDoesNotStopSchedule() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);

        scheduler.schedule("flaky", () -> {
            counter.incrementAndGet();
            throw new RuntimeException("flaky error");
        }, 0, 100);

        Thread.sleep(400);
        assertTrue(counter.get() >= 2, "Recurring task should continue despite errors");
        scheduler.cancel("flaky");
        assertTrue(sentErrors.size() >= 1);
        assertEquals("flaky", sentErrors.peek()[0]);
        assertEquals("flaky error", sentErrors.peek()[1]);
    }

    @Test
    void shutdownCancelsAllTasks() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        scheduler.schedule("s1", counter::incrementAndGet, 0, 50);
        scheduler.schedule("s2", counter::incrementAndGet, 0, 50);
        Thread.sleep(200);

        scheduler.shutdown();
        int countAtShutdown = counter.get();
        Thread.sleep(200);
        assertEquals(countAtShutdown, counter.get());
    }
}
