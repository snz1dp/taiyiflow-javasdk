package io.taiyiflow.sdk.core.session;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class StreamIdleWatchdog {
    record Arm(String runId, long recoveryGeneration, Object handleIdentity) {
    }

    static final Duration MIN_TOOL_TIMEOUT = Duration.ofMinutes(31);
    static final Duration TOOL_TIMEOUT_MARGIN = Duration.ofSeconds(60);

    /** Tool-period floor: max registered tool time + 60s, never below 31min (Electron runtime-config parity). */
    static Duration deriveToolTimeout(Duration maxRegisteredToolTime) {
        if (maxRegisteredToolTime == null) {
            return MIN_TOOL_TIMEOUT;
        }
        Duration derived = maxRegisteredToolTime.plus(TOOL_TIMEOUT_MARGIN);
        return derived.compareTo(MIN_TOOL_TIMEOUT) < 0 ? MIN_TOOL_TIMEOUT : derived;
    }

    private final ScheduledExecutorService scheduler;
    private final long idleTimeoutNanos;
    private final long toolTimeoutNanos;
    private Arm current;
    private ScheduledFuture<?> check;
    private long lastActivity;
    private int toolExecutions;

    StreamIdleWatchdog(ScheduledExecutorService scheduler, Duration idleTimeout, Duration toolTimeout) {
        if (idleTimeout.isNegative() || toolTimeout.isZero() || toolTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must be >= 0 (0 disables idle checks) and toolTimeout must be positive");
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
        if (idleTimeoutNanos > 0) {
            schedule(arm, idleTimeoutNanos, onTimeout);
        }
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

    Duration idleTimeout() {
        return Duration.ofNanos(idleTimeoutNanos);
    }

    Duration toolTimeout() {
        return Duration.ofNanos(toolTimeoutNanos);
    }

    private void reschedule(Arm arm, Runnable onTimeout) {
        if (check != null) {
            check.cancel(false);
            check = null;
        }
        long timeout = toolExecutions > 0 ? toolTimeoutNanos : idleTimeoutNanos;
        if (timeout > 0) {
            schedule(arm, timeout, onTimeout);
        }
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
