package io.taiyiflow.sdk.starter;

import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.RecoveryPolicy;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
            assertEquals(sandbox, agent.getWorkspace());
        });
    }

    @Test
    void configuresMediaAndSkillTools(@TempDir Path sandbox) throws Exception {
        Path skill = sandbox.resolve("skills/demo");
        java.nio.file.Files.createDirectories(skill);
        java.nio.file.Files.writeString(skill.resolve("SKILL.md"),
                "---\nname: Demo\n---\nUse this skill for demonstrations.\n");
        runner(sandbox)
                .withPropertyValues("taiyiflow.plugins.media=true", "taiyiflow.skills.enabled=true",
                        "taiyiflow.skills.workspace-dir=" + sandbox.resolve("skills"))
                .run(context -> {
                    TaiyiAgent agent = context.getBean(TaiyiAgent.class);
                    assertTrue(agent.getPluginManager().hasHandler("document_unstructured"));
                    assertTrue(agent.getPluginManager().hasHandler("skill_list"));
                    assertTrue(agent.getPluginManager().hasHandler("skill_fetch"));
                    assertEquals(1, agent.getSkillSupplier().supply(
                            new io.taiyiflow.sdk.core.skill.SkillQuery("flow-1", "s1", "agent", "s1", java.util.Set.of()),
                            io.taiyiflow.sdk.core.skill.SkillSelection.defaults())
                            .packages().size());
                });
    }

    @Test
    void startsWithoutOptionalBrowserClasses(@TempDir Path sandbox) {
        runner(sandbox)
                .withConfiguration(AutoConfigurations.of(TaiyiFlowBrowserAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("io.taiyiflow.sdk.browser"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("taiyiAgent"));
                });
    }

    @Test
    void usesServerDefaultFlowWhenExplicitlyEnabled(@TempDir Path sandbox) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/config", exchange -> {
            byte[] body = "{\"default_flow_id\":\"server-flow\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            runnerWithoutFlow(sandbox)
                    .withPropertyValues(
                            "taiyiflow.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                            "taiyiflow.use-default-flow=true")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals("server-flow", context.getBean(TaiyiAgent.class).getFlowId());
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesServerDefaultFlowWhenFlowIdIsOmitted(@TempDir Path sandbox) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/config", exchange -> {
            byte[] body = "{\"default_flow_id\":\"server-flow\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            runnerWithoutFlow(sandbox)
                    .withPropertyValues("taiyiflow.base-url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertEquals("server-flow", context.getBean(TaiyiAgent.class).getFlowId());
                    });
        } finally {
            server.stop(0);
        }
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
        return runnerWithoutFlow(sandbox).withPropertyValues("taiyiflow.flow-id=flow-1");
    }

    private static ApplicationContextRunner runnerWithoutFlow(Path sandbox) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaiyiFlowAutoConfiguration.class))
                .withPropertyValues(
                        "taiyiflow.base-url=http://localhost:9999",
                        "taiyiflow.api-key=test-key",
                        "taiyiflow.sandbox-root=" + sandbox,
                        "taiyiflow.plugins.shell=false",
                        "taiyiflow.plugins.files=false",
                        "taiyiflow.plugins.search=false");
    }
}
