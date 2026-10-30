package io.taiyiflow.sdk.examples;

import io.taiyiflow.sdk.collaboration.CollaborationPlugin;
import io.taiyiflow.sdk.collaboration.SchedulePlugin;

/**
 * Example: Collaboration mode - passively receive tasks from the platform.
 * This is the "headless CLI" mode where the platform pushes tasks to this agent.
 */
public class CollaborationExample {

    public static void main(String[] args) throws Exception {
        String baseUrl = System.getenv("TAIYIFLOW_BASE_URL");
        String apiKey = System.getenv("TAIYIFLOW_API_KEY");

        // Create collaboration plugin
        CollaborationPlugin collaboration = new CollaborationPlugin(baseUrl, apiKey, "collaboration_worker");

        // Handle incoming tasks
        collaboration.onTask(task -> {
            System.out.println("[Task Received] id=" + task.taskId + ", input=" + task.inputValue);

            try {
                // Simulate task processing
                collaboration.sendTaskStatus(task.taskId, "processing");
                Thread.sleep(2000);

                String result = "Processed: " + task.inputValue;
                collaboration.sendTaskResult(task.taskId, result);
                System.out.println("[Task Completed] id=" + task.taskId);
            } catch (Exception e) {
                collaboration.sendTaskError(task.taskId, e.getMessage());
            }
        });

        // Handle release pushes
        collaboration.onRelease(release -> {
            System.out.println("[Release Push] version=" + release.version);
        });

        // Start listening
        collaboration.start();
        System.out.println("Collaboration agent started. Waiting for tasks... (Ctrl+C to stop)");

        // Schedule plugin example
        SchedulePlugin scheduler = new SchedulePlugin(collaboration);
        scheduler.schedule("health-check", () -> {
            System.out.println("[Scheduled] Health check running...");
        }, 0, 60_000); // Every 60 seconds

        // Keep running
        Thread.currentThread().join();
    }
}
