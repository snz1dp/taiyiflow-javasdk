package io.taiyiflow.sdk;

import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
    void globalHooksRegistered() {
        StreamHook streamHook = new StreamHook() {};
        ToolExecutionHook toolHook = new ToolExecutionHook() {};
        PermissionHook permHook = (s, t) -> PermissionHook.Decision.ALLOW;
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
