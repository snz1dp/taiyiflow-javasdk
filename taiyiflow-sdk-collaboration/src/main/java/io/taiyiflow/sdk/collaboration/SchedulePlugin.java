package io.taiyiflow.sdk.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Schedule plugin for receiving and executing scheduled tasks.
 * Depends on an active CollaborationPlugin connection.
 */
public class SchedulePlugin {

    private static final Logger log = LoggerFactory.getLogger(SchedulePlugin.class);

    private final CollaborationPlugin collaboration;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public SchedulePlugin(CollaborationPlugin collaboration) {
        this.collaboration = collaboration;
    }

    /**
     * Schedule a recurring task.
     */
    public void schedule(String taskId, Runnable task, long initialDelayMs, long periodMs) {
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        log.error("Scheduled task {} failed", taskId, e);
                        collaboration.sendTaskError(taskId, e.getMessage());
                    }
                },
                initialDelayMs, periodMs, TimeUnit.MILLISECONDS
        );
        scheduledTasks.put(taskId, future);
        log.info("Scheduled task: {} with period {}ms", taskId, periodMs);
    }

    /**
     * Schedule a one-shot delayed task.
     */
    public void scheduleOnce(String taskId, Runnable task, long delayMs) {
        ScheduledFuture<?> future = scheduler.schedule(
                () -> {
                    try {
                        task.run();
                        collaboration.sendTaskResult(taskId, "completed");
                    } catch (Exception e) {
                        log.error("Scheduled task {} failed", taskId, e);
                        collaboration.sendTaskError(taskId, e.getMessage());
                    } finally {
                        scheduledTasks.remove(taskId);
                    }
                },
                delayMs, TimeUnit.MILLISECONDS
        );
        scheduledTasks.put(taskId, future);
    }

    /**
     * Cancel a scheduled task.
     */
    public void cancel(String taskId) {
        ScheduledFuture<?> future = scheduledTasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
            log.info("Cancelled scheduled task: {}", taskId);
        }
    }

    /**
     * Cancel all scheduled tasks and shut down.
     */
    public void shutdown() {
        scheduledTasks.values().forEach(f -> f.cancel(false));
        scheduledTasks.clear();
        scheduler.shutdown();
    }
}
