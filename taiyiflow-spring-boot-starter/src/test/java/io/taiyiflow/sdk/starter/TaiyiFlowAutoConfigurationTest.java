package io.taiyiflow.sdk.starter;

import io.taiyiflow.sdk.RecoveryPolicy;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaiyiFlowAutoConfigurationTest {

    @Test
    void collectsHookBeansAndAppliesRuntimeProperties(@TempDir Path sandbox) {
        StreamHook streamHook = new StreamHook() {
        };
        ToolExecutionHook toolHook = new ToolExecutionHook() {
        };
        PermissionHook permissionHook = (sessionId, toolCall) ->
                CompletableFuture.completedFuture(PermissionHook.Decision.ALLOW);
        SessionHook sessionHook = new SessionHook() {
        };

        runner(sandbox)
                .withPropertyValues(
                        "taiyiflow.event-queue-capacity=77",
                        "taiyiflow.sse-fallback-enabled=false",
                        "taiyiflow.recovery-policy=AUTO",
                        "taiyiflow.recovery-min-backoff=25ms",
                        "taiyiflow.recovery-max-backoff=250ms",
                        "taiyiflow.recovery-max-attempts=4",
                        "taiyiflow.auto-restart-on-execution-loss=false",
                        "taiyiflow.permission-timeout=45s")
                .withBean("testStreamHook", StreamHook.class, () -> streamHook)
                .withBean("testToolHook", ToolExecutionHook.class, () -> toolHook)
                .withBean("testPermissionHook", PermissionHook.class, () -> permissionHook)
                .withBean("testSessionHook", SessionHook.class, () -> sessionHook)
                .run(context -> {
                    if (context.getStartupFailure() != null) {
                        throw new AssertionError("Application context failed", context.getStartupFailure());
                    }
                    TaiyiAgent agent = context.getBean(TaiyiAgent.class);
                    assertSame(streamHook, agent.getGlobalStreamHooks().get(0));
                    assertSame(toolHook, agent.getGlobalToolHooks().get(0));
                    assertSame(permissionHook, agent.getGlobalPermissionHooks().get(0));
                    assertSame(sessionHook, agent.getGlobalSessionHooks().get(0));
                    assertEquals(77, agent.getEventQueueCapacity());
                    assertFalse(agent.isSseFallbackEnabled());
                    assertEquals(RecoveryPolicy.AUTO, agent.getRecoveryPolicy());
                    assertEquals(Duration.ofMillis(25), agent.getRecoveryMinBackoff());
                    assertEquals(Duration.ofMillis(250), agent.getRecoveryMaxBackoff());
                    assertEquals(4, agent.getRecoveryMaxAttempts());
                    assertFalse(agent.isAutoRestartOnExecutionLoss());
                    assertEquals(Duration.ofSeconds(45), agent.getPermissionTimeout());
                });
    }

    @Test
    void appliesExecutionControlDefaults(@TempDir Path sandbox) {
        runner(sandbox).run(context -> {
            TaiyiAgent agent = context.getBean(TaiyiAgent.class);
            assertEquals(RecoveryPolicy.MANUAL, agent.getRecoveryPolicy());
            assertEquals(Duration.ofSeconds(2), agent.getRecoveryMinBackoff());
            assertEquals(Duration.ofSeconds(60), agent.getRecoveryMaxBackoff());
            assertEquals(8, agent.getRecoveryMaxAttempts());
            assertTrue(agent.isAutoRestartOnExecutionLoss());
            assertEquals(Duration.ofSeconds(300), agent.getPermissionTimeout());
        });
    }

    @Test
    void backsOffWithoutRequiredConnectionProperties() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaiyiFlowAutoConfiguration.class))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("taiyiAgent"));
                });
    }

    private static ApplicationContextRunner runner(Path sandbox) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaiyiFlowAutoConfiguration.class))
                .withPropertyValues(
                        "taiyiflow.base-url=http://localhost:9999",
                        "taiyiflow.api-key=test-key",
                        "taiyiflow.flow-id=flow-1",
                        "taiyiflow.sandbox-root=" + sandbox,
                        "taiyiflow.plugins.shell=false",
                        "taiyiflow.plugins.files=false",
                        "taiyiflow.plugins.search=false");
    }
}
