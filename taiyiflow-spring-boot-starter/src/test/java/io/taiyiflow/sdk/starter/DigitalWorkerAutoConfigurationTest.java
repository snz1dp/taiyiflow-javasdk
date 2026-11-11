package io.taiyiflow.sdk.starter;

import io.taiyiflow.sdk.RecoveryPolicy;
import io.taiyiflow.sdk.worker.ApprovalChannel;
import io.taiyiflow.sdk.worker.DigitalWorker;
import io.taiyiflow.sdk.worker.WorkerIdentityValidator;
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
    void requiresPlatformIdentityValidator(@TempDir Path workspace) {
        runner(workspace)
                .withBean(WorkerAuth.class, () -> WorkerAuth.apiKey("api-key"))
                .run(context -> assertTrue(context.getStartupFailure().getMessage().contains("identityValidator")));
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
