package io.taiyiflow.sdk.starter;

import io.taiyiflow.sdk.RecoveryPolicy;
import io.taiyiflow.sdk.worker.ApprovalChannel;
import io.taiyiflow.sdk.worker.DigitalWorker;
import io.taiyiflow.sdk.worker.WorkerIdentityValidator;
import io.taiyiflow.sdk.worker.WorkerRegistrar;
import io.taiyiflow.sdk.worker.WorkerRegistrationResult;
import io.taiyiflow.sdk.worker.WorkerIdentity;
import io.taiyiflow.sdk.worker.WorkerState;
import io.taiyiflow.sdk.worker.auth.WorkerAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigitalWorkerAutoConfigurationTest {

    @Test
    void assemblesWorkerWithoutStartingConnection(@TempDir Path workspace) {
        runner(workspace)
                .withBean(WorkerAuth.class, () -> WorkerAuth.jwt("jwt-token"))
                .withBean(WorkerIdentityValidator.class, () -> (identity, role, abilities) -> { })
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    DigitalWorker worker = context.getBean(DigitalWorker.class);
                    assertEquals(WorkerState.OFFLINE, worker.getState());
                    assertEquals(RecoveryPolicy.AUTO, worker.getRecoveryPolicy());
                    assertEquals(ApprovalChannel.PLATFORM, worker.getApprovalChannel());
                    assertFalse(context.containsBean("digitalWorkerLifecycle"));
                });
    }

    @Test
    void appliesWorkerRequestDefaults(@TempDir Path workspace) {
        runner(workspace)
                .withPropertyValues(
                        "taiyiflow.worker.request.model=qwen-max",
                        "taiyiflow.worker.request.auto-loop-enabled=true",
                        "taiyiflow.worker.instructions.directories=" + workspace.resolve("instructions"))
                .withBean(WorkerAuth.class, () -> WorkerAuth.apiKey("api-key"))
                .withBean(WorkerIdentityValidator.class, () -> (identity, role, abilities) -> { })
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    DigitalWorker worker = context.getBean(DigitalWorker.class);
                    assertEquals("qwen-max", worker.getRequestDefaults().getModel());
                    assertTrue(worker.getRequestDefaults().isAutoLoopEnabled());
                    assertEquals("chat", worker.getRequestDefaults().getInputType());
                    assertEquals(1, worker.getInstructionDirectories().size());
                });
    }

    @Test
    void appliesWorkerWatchdogTimeouts(@TempDir Path workspace) {
        runner(workspace)
                .withPropertyValues(
                        "taiyiflow.worker.stream-idle-timeout=30s",
                        "taiyiflow.worker.tool-execution-timeout=45m")
                .withBean(WorkerAuth.class, () -> WorkerAuth.apiKey("api-key"))
                .withBean(WorkerIdentityValidator.class, () -> (identity, role, abilities) -> { })
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    DigitalWorker worker = context.getBean(DigitalWorker.class);
                    assertEquals(java.time.Duration.ofSeconds(30), worker.getStreamIdleTimeout());
                    assertEquals(java.time.Duration.ofMinutes(45), worker.getToolExecutionTimeout());
                });
        runner(workspace)
                .withBean(WorkerAuth.class, () -> WorkerAuth.apiKey("api-key"))
                .withBean(WorkerIdentityValidator.class, () -> (identity, role, abilities) -> { })
                .run(context -> {
                    DigitalWorker worker = context.getBean(DigitalWorker.class);
                    assertEquals(java.time.Duration.ofSeconds(1200), worker.getStreamIdleTimeout());
                    assertNull(worker.getToolExecutionTimeout());
                });
    }

    @Test
    void requiresPlatformIdentityValidator(@TempDir Path workspace) {
        runner(workspace)
                .withBean(WorkerAuth.class, () -> WorkerAuth.apiKey("api-key"))
                .run(context -> assertTrue(context.getStartupFailure().getMessage().contains("identityValidator")));
    }

    @Test
    void assemblesRegistrarWithoutConfiguredWorkerId(@TempDir Path workspace) {
        runner(workspace)
                .withPropertyValues("taiyiflow.worker.worker-id=")
                .withBean(WorkerAuth.class, () -> WorkerAuth.apiKey("api-key"))
                .withBean(WorkerRegistrar.class, () -> request -> WorkerRegistrationResult.of(
                        WorkerIdentity.of("registered-worker", request.displayName())))
                .withBean(WorkerIdentityValidator.class, () -> (identity, role, abilities) -> { })
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(WorkerState.OFFLINE, context.getBean(DigitalWorker.class).getState());
                });
    }

    @Test
    void backsOffWhenWorkerIsDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DigitalWorkerAutoConfiguration.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("digitalWorker"));
                });
    }

    @Test
    void appliesToolClientTypeAndConcurrencyProperties(@TempDir Path workspace) {
        runner(workspace)
                .withPropertyValues(
                        "taiyiflow.worker.builtin-tools=true",
                        "taiyiflow.worker.allowed-shell-commands=sleep",
                        "taiyiflow.worker.shell-command-timeout=45s",
                        "taiyiflow.worker.subtask-max-tasks=8",
                        "taiyiflow.worker.subtask-max-parallelism=2",
                        "taiyiflow.worker.subtask-timeout=30m",
                        "taiyiflow.worker.max-session-concurrency=2",
                        "taiyiflow.worker.request.client-type=desktop")
                .withBean(WorkerAuth.class, () -> WorkerAuth.apiKey("api-key"))
                .withBean(WorkerIdentityValidator.class, () -> (identity, role, abilities) -> { })
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    DigitalWorker worker = context.getBean(DigitalWorker.class);
                    assertEquals("desktop", field(worker, "clientType"));
                    assertEquals(java.time.Duration.ofSeconds(45), field(worker, "shellCommandTimeout"));
                    assertEquals(8, field(worker, "subtaskMaxTasks"));
                    assertEquals(2, field(worker, "subtaskMaxParallelism"));
                    assertEquals(java.time.Duration.ofMinutes(30), field(worker, "subtaskTimeout"));
                    assertEquals(2, field(worker, "maxSessionConcurrency"));
                });
    }

    private static Object field(DigitalWorker worker, String name) throws Exception {
        java.lang.reflect.Field field = DigitalWorker.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(worker);
    }

    private static ApplicationContextRunner runner(Path workspace) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DigitalWorkerAutoConfiguration.class))
                .withPropertyValues(
                        "taiyiflow.base-url=http://127.0.0.1:1",
                        "taiyiflow.worker.enabled=true",
                        "taiyiflow.worker.auto-start=false",
                        "taiyiflow.worker.worker-id=worker-1",
                        "taiyiflow.worker.display-name=Test Worker",
                        "taiyiflow.worker.workspace=" + workspace);
    }
}
