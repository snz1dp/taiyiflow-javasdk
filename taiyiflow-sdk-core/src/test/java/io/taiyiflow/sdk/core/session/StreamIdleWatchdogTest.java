package io.taiyiflow.sdk.core.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamIdleWatchdogTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void closeScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    void validActivityRefreshesIdleDeadline() throws Exception {
        StreamIdleWatchdog watchdog = new StreamIdleWatchdog(
                scheduler, Duration.ofMillis(100), Duration.ofSeconds(1));
        CountDownLatch timedOut = new CountDownLatch(1);
        StreamIdleWatchdog.Arm arm = watchdog.start("run-1", 1, new Object(), timedOut::countDown);

        for (int i = 0; i < 3; i++) {
            Thread.sleep(45);
            watchdog.activity(arm, timedOut::countDown);
        }
        assertEquals(1, timedOut.getCount());
        assertTrue(timedOut.await(1, TimeUnit.SECONDS));
    }

    @Test
    void replacedArmCannotTimeOutNewStream() throws Exception {
        StreamIdleWatchdog watchdog = new StreamIdleWatchdog(
                scheduler, Duration.ofMillis(20), Duration.ofSeconds(1));
        AtomicInteger oldTimeouts = new AtomicInteger();
        StreamIdleWatchdog.Arm old = watchdog.start("run-old", 1, new Object(), oldTimeouts::incrementAndGet);
        StreamIdleWatchdog.Arm current = watchdog.start("run-new", 2, new Object(), () -> { });

        watchdog.stop(old);
        Thread.sleep(60);
        assertEquals(0, oldTimeouts.get());
        watchdog.stop(current);
    }

    @Test
    void toolExecutionUsesBoundedToolTimeout() throws Exception {
        StreamIdleWatchdog watchdog = new StreamIdleWatchdog(
                scheduler, Duration.ofMillis(30), Duration.ofMillis(180));
        CountDownLatch timedOut = new CountDownLatch(1);
        StreamIdleWatchdog.Arm arm = watchdog.start("run-1", 1, new Object(), timedOut::countDown);

        watchdog.toolStarted(arm, timedOut::countDown);
        Thread.sleep(80);
        assertEquals(1, timedOut.getCount());
        watchdog.toolFinished(arm, timedOut::countDown);
        assertTrue(timedOut.await(1, TimeUnit.SECONDS));
    }

    @Test
    void derivesToolTimeoutWithMarginAndFloor() {
        assertEquals(Duration.ofMinutes(31), StreamIdleWatchdog.deriveToolTimeout(null));
        assertEquals(Duration.ofMinutes(31), StreamIdleWatchdog.deriveToolTimeout(Duration.ofMinutes(29)));
        assertEquals(Duration.ofMinutes(60), StreamIdleWatchdog.deriveToolTimeout(Duration.ofMinutes(59)));
        assertEquals(Duration.ofMinutes(61), StreamIdleWatchdog.deriveToolTimeout(Duration.ofHours(1)));
    }

    @Test
    void scaledMediaAndSubtaskToolPeriodsSurviveIdleWindow() throws Exception {
        // 1200s idle / 60min media tool period / 61min subtask tool period, scaled by 1/60000.
        long idleMillis = Duration.ofSeconds(1200).toMillis() / 60000;
        long mediaMillis = StreamIdleWatchdog.deriveToolTimeout(Duration.ofMinutes(59)).toMillis() / 60000;
        long subtaskMillis = StreamIdleWatchdog.deriveToolTimeout(Duration.ofHours(1)).toMillis() / 60000;
        for (long toolMillis : new long[]{mediaMillis, subtaskMillis}) {
            StreamIdleWatchdog watchdog = new StreamIdleWatchdog(
                    scheduler, Duration.ofMillis(idleMillis), Duration.ofMillis(toolMillis));
            CountDownLatch timedOut = new CountDownLatch(1);
            StreamIdleWatchdog.Arm arm = watchdog.start("run-1", 1, new Object(), timedOut::countDown);
            watchdog.toolStarted(arm, timedOut::countDown);
            Thread.sleep(idleMillis * 2);
            assertEquals(1, timedOut.getCount(), "long tool must not be cut by the idle window");
            watchdog.toolFinished(arm, timedOut::countDown);
            assertTrue(timedOut.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void zeroIdleTimeoutDisablesIdleChecksButKeepsToolGuard() throws Exception {
        StreamIdleWatchdog watchdog = new StreamIdleWatchdog(
                scheduler, Duration.ZERO, Duration.ofMillis(80));
        CountDownLatch timedOut = new CountDownLatch(1);
        StreamIdleWatchdog.Arm arm = watchdog.start("run-1", 1, new Object(), timedOut::countDown);
        Thread.sleep(120);
        assertEquals(1, timedOut.getCount(), "idle checks disabled");
        watchdog.toolStarted(arm, timedOut::countDown);
        assertTrue(timedOut.await(1, TimeUnit.SECONDS), "tool-period guard still active");
    }
}
