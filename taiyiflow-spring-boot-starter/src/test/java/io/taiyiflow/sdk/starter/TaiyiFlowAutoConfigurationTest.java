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
    void bindsFineGrainedPermissionSettings(@TempDir Path sandbox) {
        runner(sandbox)
                .withPropertyValues(
                        "taiyiflow.permission-policy=AUTO",
                        "taiyiflow.permission.rules[0].action=deny",
                        "taiyiflow.permission.rules[0].command-prefix=rm",
                        "taiyiflow.permission.rules[1].action=bogus",
                        "taiyiflow.permission.rules[1].tool-name=shell",
                        "taiyiflow.permission.write-confirmation=direct",
                        "taiyiflow.permission.browser-permission-mode=skip_all_permission_checks",
                        "taiyiflow.permission.categories.shell=auto",
                        "taiyiflow.permission.categories.bogus=auto")
                .run(context -> {
                    if (context.getStartupFailure() != null) {
                        throw new AssertionError("Application context failed", context.getStartupFailure());
                    }
                    TaiyiAgent agent = context.getBean(TaiyiAgent.class);
                    var settings = agent.getPermissionGate().getSettings();
                    assertEquals(io.taiyiflow.sdk.hooks.PermissionPolicy.AUTO, settings.getPolicy());
                    assertEquals(io.taiyiflow.sdk.core.security.WriteConfirmation.DIRECT,
                            settings.getWriteConfirmation());
                    assertEquals(io.taiyiflow.sdk.core.security.BrowserPermissionMode.SKIP_ALL_PERMISSION_CHECKS,
                            settings.getBrowserPermissionMode());
                    assertEquals(2, settings.getRules().size());
                    assertEquals(io.taiyiflow.sdk.core.security.PermissionRule.Action.ASK,
                            settings.getRules().get(1).action());
                    assertEquals(1, settings.getCategoryPolicies().size());
                    assertEquals(io.taiyiflow.sdk.hooks.PermissionPolicy.AUTO,
                            settings.getCategoryPolicies().get(io.taiyiflow.sdk.core.security.ToolCategory.SHELL));
                });
    }

    @Test
    void permissionDefaultsAreReadonlyConfirmAsk(@TempDir Path sandbox) {
        runner(sandbox).run(context -> {
            TaiyiAgent agent = context.getBean(TaiyiAgent.class);
            var settings = agent.getPermissionGate().getSettings();
            assertEquals(io.taiyiflow.sdk.hooks.PermissionPolicy.READONLY, settings.getPolicy());
            assertEquals(io.taiyiflow.sdk.core.security.WriteConfirmation.CONFIRM,
                    settings.getWriteConfirmation());
            assertEquals(io.taiyiflow.sdk.core.security.BrowserPermissionMode.ASK,
                    settings.getBrowserPermissionMode());
            assertTrue(settings.getRules().isEmpty());
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

    @Test
    void appliesRequestDefaultsAndInstructionDirectories(@TempDir Path sandbox) {
        runner(sandbox)
                .withPropertyValues(
                        "taiyiflow.request.model=qwen-max",
                        "taiyiflow.request.reasoning=true",
                        "taiyiflow.request.auto-loop-enabled=true",
                        "taiyiflow.request.auto-loop-max-rounds=6",
                        "taiyiflow.request.image-generation=ON",
                        "taiyiflow.request.group-id=group-1",
                        "taiyiflow.request.relation-sessions=s-a,s-b",
                        "taiyiflow.request.knowledge-scopes=scope-a,scope-b",
                        "taiyiflow.request.knowledge-docs=doc-1",
                        "taiyiflow.instructions.directories=" + sandbox.resolve("instructions"))
                .run(context -> {
                    TaiyiAgent agent = context.getBean(TaiyiAgent.class);
                    assertEquals("qwen-max", agent.getRequestDefaults().getModel());
                    assertEquals(Boolean.TRUE, agent.getRequestDefaults().getReasoning());
                    assertTrue(agent.getRequestDefaults().isAutoLoopEnabled());
                    assertEquals(6, agent.getRequestDefaults().getAutoLoopMaxRounds());
                    assertEquals(io.taiyiflow.sdk.RequestDefaults.ImageGeneration.ON,
                            agent.getRequestDefaults().getImageGeneration());
                    assertEquals("group-1", agent.getRequestDefaults().getGroupId());
                    assertEquals(java.util.List.of("s-a", "s-b"), agent.getRequestDefaults().getRelationSessions());
                    assertEquals("scope-a", agent.getRequestDefaults().getKnowledgeScopes().get(0).asText());
                    assertEquals("scope-b", agent.getRequestDefaults().getKnowledgeScopes().get(1).asText());
                    assertEquals("doc-1", agent.getRequestDefaults().getKnowledgeDocs().get(0).asText());
                    assertEquals("chat", agent.getRequestDefaults().getInputType());
                    assertEquals("chat", agent.getRequestDefaults().getOutputType());
                    assertEquals(java.util.List.of(sandbox.resolve("instructions").toAbsolutePath().normalize()),
                            agent.getInstructionDirectories());
                });
        runner(sandbox).run(context -> {
            TaiyiAgent agent = context.getBean(TaiyiAgent.class);
            assertFalse(agent.getRequestDefaults().isAutoLoopEnabled());
            assertEquals("chat", agent.getRequestDefaults().getInputType());
            assertEquals(io.taiyiflow.sdk.RequestDefaults.ImageGeneration.AUTO,
                    agent.getRequestDefaults().getImageGeneration());
            assertTrue(agent.getInstructionDirectories().isEmpty());
        });
    }

    @Test
    void appliesWatchdogTimeouts(@TempDir Path sandbox) {
        runner(sandbox)
                .withPropertyValues("taiyiflow.stream-idle-timeout=0s", "taiyiflow.tool-execution-timeout=40m")
                .run(context -> {
                    TaiyiAgent agent = context.getBean(TaiyiAgent.class);
                    assertEquals(Duration.ZERO, agent.getStreamIdleTimeout());
                    assertEquals(Duration.ofMinutes(40), agent.getToolExecutionTimeout());
                });
        runner(sandbox).run(context -> {
            TaiyiAgent agent = context.getBean(TaiyiAgent.class);
            assertEquals(Duration.ofSeconds(1200), agent.getStreamIdleTimeout());
            assertNull(agent.getToolExecutionTimeout());
        });
    }

    @Test
    void appliesClientTypeConcurrencyAndShellTimeoutProperties(@TempDir Path sandbox) {
        runner(sandbox)
                .withPropertyValues(
                        "taiyiflow.request.client-type=desktop",
                        "taiyiflow.max-session-concurrency=5",
                        "taiyiflow.plugins.shell=true",
                        "taiyiflow.plugins.shell-command-timeout=45s")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    TaiyiAgent agent = context.getBean(TaiyiAgent.class);
                    assertEquals("desktop", agent.getClientType());
                    assertEquals(5, agent.getMaxSessionConcurrency());
                    assertTrue(agent.getPluginManager().hasHandler("shell"));
                    assertEquals(Duration.ofSeconds(45),
                            context.getBean(TaiyiFlowProperties.class).getPlugins().getShellCommandTimeout());
                });
        runner(sandbox).run(context -> {
            assertEquals("device", context.getBean(TaiyiAgent.class).getClientType());
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
