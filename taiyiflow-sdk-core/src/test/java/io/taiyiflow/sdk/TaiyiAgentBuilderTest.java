package io.taiyiflow.sdk;

import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TaiyiAgentBuilderTest {

    @Test
    void buildWithRequiredFields() {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://localhost:8080")
                .apiKey("key-123")
                .flowId("flow-abc")
                .build();

        assertEquals("http://localhost:8080", agent.getBaseUrl());
        assertEquals("key-123", agent.getApiKey());
        assertEquals("flow-abc", agent.getFlowId());
    }

    @Test
    void dynamicAccessTokenIsReadForEachUse() {
        AtomicReference<String> token = new AtomicReference<>("old-token");
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://localhost:8080")
                .accessToken(token::get)
                .flowId("flow-abc")
                .build();

        assertEquals("old-token", agent.getApiKey());
        token.set("new-token");
        assertEquals("new-token", agent.getApiKey());
        assertEquals("new-token", agent.getAccessToken().get());
        agent.shutdown();
    }

    @Test
    void missingBaseUrlThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                TaiyiAgent.builder().apiKey("k").flowId("f").build());
    }

    @Test
    void missingApiKeyThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                TaiyiAgent.builder().baseUrl("http://x").flowId("f").build());
    }

    @Test
    void missingFlowIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                TaiyiAgent.builder().baseUrl("http://x").apiKey("k").build());
    }

    @Test
    void blankBaseUrlThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                TaiyiAgent.builder().baseUrl("  ").apiKey("k").flowId("f").build());
    }

    @Test
    void customClientIdentityIsSharedWithExecutionControl() {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://x").apiKey("k").flowId("f")
                .clientId("client-1")
                .deviceId("device-1")
                .build();

        assertEquals("client-1", agent.getClientId());
        assertEquals("device-1", agent.getDeviceId());
        assertNotNull(agent.executionControl());
        agent.shutdown();
    }

    @Test
    void defaultTimeouts() {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://x").apiKey("k").flowId("f").build();
        assertEquals(Duration.ofSeconds(10), agent.getConnectTimeout());
        assertEquals(Duration.ofSeconds(30), agent.getRequestTimeout());
    }

    @Test
    void customTimeouts() {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://x").apiKey("k").flowId("f")
                .connectTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(60))
                .build();
        assertEquals(Duration.ofSeconds(5), agent.getConnectTimeout());
        assertEquals(Duration.ofSeconds(60), agent.getRequestTimeout());
    }

    @Test
    void permissionPolicyDefaultsToReadOnly() {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://x").apiKey("k").flowId("f").build();

        assertEquals(PermissionPolicy.READONLY, agent.getPermissionPolicy());
        agent.shutdown();
    }

    @Test
    void permissionTimeoutDefaultsAndValidation() {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://x").apiKey("k").flowId("f").build();
        assertEquals(Duration.ofSeconds(300), agent.getPermissionTimeout());
        agent.shutdown();

        TaiyiAgent infinite = TaiyiAgent.builder()
                .baseUrl("http://x").apiKey("k").flowId("f")
                .permissionTimeout(Duration.ZERO)
                .build();
        assertEquals(Duration.ZERO, infinite.getPermissionTimeout());
        infinite.shutdown();

        TaiyiAgent.Builder builder = TaiyiAgent.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.permissionTimeout(Duration.ofSeconds(9)));
        assertThrows(IllegalArgumentException.class, () -> builder.permissionTimeout(Duration.ofSeconds(6001)));
        assertThrows(IllegalArgumentException.class, () -> builder.permissionTimeout(null));
    }

    @Test
    void recoveryDefaultsToManual() {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://x").apiKey("k").flowId("f").build();

        assertEquals(RecoveryPolicy.MANUAL, agent.getRecoveryPolicy());
        assertEquals(Duration.ofSeconds(2), agent.getRecoveryMinBackoff());
        assertEquals(Duration.ofSeconds(60), agent.getRecoveryMaxBackoff());
        assertEquals(8, agent.getRecoveryMaxAttempts());
        assertTrue(agent.isAutoRestartOnExecutionLoss());
        agent.shutdown();
    }

    @Test
    void customRecoveryConfiguration() {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://x").apiKey("k").flowId("f")
                .recoveryPolicy(RecoveryPolicy.AUTO)
                .recoveryBackoff(Duration.ofMillis(10), Duration.ofSeconds(2))
                .recoveryMaxAttempts(3)
                .autoRestartOnExecutionLoss(false)
                .build();

        assertEquals(RecoveryPolicy.AUTO, agent.getRecoveryPolicy());
        assertEquals(Duration.ofMillis(10), agent.getRecoveryMinBackoff());
        assertEquals(Duration.ofSeconds(2), agent.getRecoveryMaxBackoff());
        assertEquals(3, agent.getRecoveryMaxAttempts());
        assertFalse(agent.isAutoRestartOnExecutionLoss());
        agent.shutdown();
    }

    @Test
    void invalidRecoveryConfigurationThrows() {
        TaiyiAgent.Builder builder = TaiyiAgent.builder();
        assertThrows(NullPointerException.class, () -> builder.recoveryPolicy(null));
        assertThrows(IllegalArgumentException.class, () ->
                builder.recoveryBackoff(Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
                builder.recoveryBackoff(Duration.ofSeconds(2), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> builder.recoveryMaxAttempts(0));
    }

    @Test
    void globalHooksRegistered() {
        StreamHook streamHook = new StreamHook() {};
        ToolExecutionHook toolHook = new ToolExecutionHook() {};
        PermissionHook permHook = (s, t) ->
                CompletableFuture.completedFuture(PermissionHook.Decision.ALLOW);
        SessionHook sessionHook = new SessionHook() {};

        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://x").apiKey("k").flowId("f")
                .onStream(streamHook)
                .onToolExecution(toolHook)
                .onPermission(permHook)
                .onLifecycle(sessionHook)
                .build();

        assertEquals(1, agent.getGlobalStreamHooks().size());
        assertEquals(1, agent.getGlobalToolHooks().size());
        assertEquals(1, agent.getGlobalPermissionHooks().size());
        assertEquals(1, agent.getGlobalSessionHooks().size());
    }

    @Test
    void collaborationIsDisabledByDefault() {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://localhost").apiKey("k").flowId("f").build();

        assertNull(agent.getCollaborationPlugin());
        agent.shutdown();
    }

    @Test
    void enabledCollaborationStartsAndStopsWithAgent() {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://127.0.0.1:1").apiKey("k").flowId("f")
                .collaborationClientId("client-1")
                .enableCollaboration()
                .build();

        assertNotNull(agent.getCollaborationPlugin());
        assertTrue(agent.getCollaborationPlugin().isRunning());
        agent.shutdown();
        assertFalse(agent.getCollaborationPlugin().isRunning());
    }

    @Test
    void newSessionCreatesUniqueSession() {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://x").apiKey("k").flowId("f").build();
        AgentSession s1 = agent.newSession();
        AgentSession s2 = agent.newSession();
        assertNotEquals(s1.getSessionId(), s2.getSessionId());
        agent.shutdown();
    }

    @Test
    void newSessionWithCustomId() {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl("http://x").apiKey("k").flowId("f").build();
        AgentSession session = agent.newSession("custom-id");
        assertEquals("custom-id", session.getSessionId());
        agent.shutdown();
    }
}
