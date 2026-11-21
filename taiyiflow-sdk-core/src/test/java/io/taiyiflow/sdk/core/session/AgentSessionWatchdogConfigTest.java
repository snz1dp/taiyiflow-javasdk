package io.taiyiflow.sdk.core.session;

import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentSessionWatchdogConfigTest {

    private static TaiyiAgent.Builder builder() {
        return TaiyiAgent.builder().baseUrl("http://127.0.0.1:1").apiKey("key").flowId("flow");
    }

    @Test
    void defaultsAlignWithElectronIdleAndDerivedFloor() {
        TaiyiAgent agent = builder().build();
        try {
            AgentSessionImpl session = (AgentSessionImpl) agent.newSession();
            assertEquals(Duration.ofSeconds(1200), session.streamWatchdog().idleTimeout());
            assertEquals(Duration.ofMinutes(31), session.streamWatchdog().toolTimeout());
        } finally {
            agent.shutdown();
        }
    }

    @Test
    void toolTimeoutDerivesFromRegisteredToolRegistry() {
        TaiyiAgent agent = builder().build();
        try {
            agent.getPluginManager().register("long_tool",
                    call -> ToolResult.success(call.getId(), null), Duration.ofMinutes(59));
            AgentSessionImpl session = (AgentSessionImpl) agent.newSession();
            assertEquals(Duration.ofMinutes(60), session.streamWatchdog().toolTimeout());

            agent.getPluginManager().register("run_subtask",
                    call -> ToolResult.success(call.getId(), null), Duration.ofHours(1));
            AgentSessionImpl subtaskSession = (AgentSessionImpl) agent.newSession();
            assertEquals(Duration.ofMinutes(61), subtaskSession.streamWatchdog().toolTimeout());
        } finally {
            agent.shutdown();
        }
    }

    @Test
    void explicitOverrideAppliesButNeverBelowDerivedFloor() {
        TaiyiAgent agent = builder()
                .streamIdleTimeout(Duration.ZERO)
                .toolExecutionTimeout(Duration.ofMinutes(40))
                .build();
        try {
            AgentSessionImpl session = (AgentSessionImpl) agent.newSession();
            assertEquals(Duration.ZERO, session.streamWatchdog().idleTimeout());
            assertEquals(Duration.ofMinutes(40), session.streamWatchdog().toolTimeout());

            agent.getPluginManager().register("long_tool",
                    call -> ToolResult.success(call.getId(), null), Duration.ofHours(1));
            AgentSessionImpl clamped = (AgentSessionImpl) agent.newSession();
            assertEquals(Duration.ofMinutes(61), clamped.streamWatchdog().toolTimeout());
        } finally {
            agent.shutdown();
        }
    }

    @Test
    void rejectsInvalidWatchdogTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> builder().streamIdleTimeout(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> builder().toolExecutionTimeout(Duration.ZERO));
    }
}