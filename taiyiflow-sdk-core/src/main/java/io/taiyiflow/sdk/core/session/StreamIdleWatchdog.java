package io.taiyiflow.sdk.core.session;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class StreamIdleWatchdog {
    record Arm(String runId, long recoveryGeneration, Object handleIdentity) {
    }

    private final ScheduledExecutorService scheduler;
    private final long idleTimeoutNanos;
    private final long toolTimeoutNanos;
    private Arm current;
    private ScheduledFuture<?> check;
    private long lastActivity;
    private int toolExecutions;

    StreamIdleWatchdog(ScheduledExecutorService scheduler, Duration idleTimeout, Duration toolTimeout) {
        if (idleTimeout.isZero() || idleTimeout.isNegative() || toolTimeout.isZero() || toolTimeout.isNegative()) {
            throw new IllegalArgumentException("Watchdog timeouts must be positive");
        }
        this.scheduler = scheduler;
        this.idleTimeoutNanos = idleTimeout.toNanos();
        this.toolTimeoutNanos = toolTimeout.toNanos();
    }

    synchronized Arm start(String runId, long recoveryGeneration, Object handleIdentity, Runnable onTimeout) {
        stopCurrent();
        Arm arm = new Arm(runId, recoveryGeneration, handleIdentity);
        current = arm;
        lastActivity = System.nanoTime();
        toolExecutions = 0;
        schedule(arm, idleTimeoutNanos, onTimeout);
        return arm;
    }

    synchronized void activity(Arm arm, Runnable onTimeout) {
        if (current != arm) {
            return;
        }
        lastActivity = System.nanoTime();
        reschedule(arm, onTimeout);
    }

    synchronized void toolStarted(Arm arm, Runnable onTimeout) {
        if (current != arm) {
            return;
        }
        toolExecutions++;
        lastActivity = System.nanoTime();
        reschedule(arm, onTimeout);
    }

    synchronized void toolFinished(Arm arm, Runnable onTimeout) {
        if (current != arm) {
            return;
        }
        toolExecutions = Math.max(0, toolExecutions - 1);
        lastActivity = System.nanoTime();
        reschedule(arm, onTimeout);
    }

    synchronized void stop(Arm arm) {
        if (current == arm) {
            stopCurrent();
        }
    }

    private void reschedule(Arm arm, Runnable onTimeout) {
        if (check != null) {
            check.cancel(false);
        }
        schedule(arm, toolExecutions > 0 ? toolTimeoutNanos : idleTimeoutNanos, onTimeout);
    }

    private void schedule(Arm arm, long delayNanos, Runnable onTimeout) {
        check = scheduler.schedule(() -> check(arm, onTimeout), delayNanos, TimeUnit.NANOSECONDS);
    }

    private void check(Arm arm, Runnable onTimeout) {
        synchronized (this) {
            if (current != arm) {
                return;
            }
            long timeout = toolExecutions > 0 ? toolTimeoutNanos : idleTimeoutNanos;
            long remaining = lastActivity + timeout - System.nanoTime();
            if (remaining > 0) {
                schedule(arm, remaining, onTimeout);
                return;
            }
            current = null;
            check = null;
        }
        onTimeout.run();
    }

    private void stopCurrent() {
        current = null;
        toolExecutions = 0;
        if (check != null) {
            check.cancel(false);
            check = null;
        }
    }
}
