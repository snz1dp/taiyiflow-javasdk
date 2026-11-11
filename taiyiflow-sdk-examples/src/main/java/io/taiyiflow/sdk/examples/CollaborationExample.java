package io.taiyiflow.sdk.examples;

import io.taiyiflow.sdk.collaboration.CollaborationPlugin;
import io.taiyiflow.sdk.worker.PlatformTaskReporter;
import io.taiyiflow.sdk.worker.TaskExecutor;
import io.taiyiflow.sdk.worker.auth.WorkerAuth;

import java.time.Duration;
import java.util.Map;

/**
 * Example: Collaboration mode - passively receive tasks from the platform.
 * This is the "headless CLI" mode where the platform pushes tasks to this agent.
 */
public class CollaborationExample {

    public static void main(String[] args) throws Exception {
        String baseUrl = System.getenv("TAIYIFLOW_BASE_URL");
        String apiKey = System.getenv("TAIYIFLOW_API_KEY");

        WorkerAuth auth = WorkerAuth.apiKey(apiKey);
        TaskExecutor executor = new TaskExecutor(
                (task, progress) -> Map.of("summary", "Processed: " + task.inputValue),
                new PlatformTaskReporter(baseUrl, auth, Duration.ofSeconds(30), Map.of()));
        CollaborationPlugin collaboration = new CollaborationPlugin(baseUrl, apiKey, "collaboration_worker");

        collaboration.onTask(task -> {
            System.out.println("[Task Received] id=" + task.taskId + ", input=" + task.inputValue);
            System.out.println("[Task Finished] id=" + task.taskId + ", state=" + executor.execute(task));
        });

        // Handle release pushes
        collaboration.onRelease(release -> {
            System.out.println("[Release Push] version=" + release.version);
        });

        // Start listening
        collaboration.start();
        System.out.println("Collaboration agent started. Waiting for tasks... (Ctrl+C to stop)");

        // Keep running
        Thread.currentThread().join();
    }
}
