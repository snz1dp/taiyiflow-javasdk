package io.taiyiflow.sdk;

import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;

import java.util.function.Function;

/**
 * A single agent session representing one conversation.
 * Sessions are created by {@link TaiyiAgent} and support streaming chat.
 */
public interface AgentSession {

    /**
     * Get the unique session identifier.
     */
    String getSessionId();

    /**
     * Send a message and start streaming the response.
     * Events are delivered to the registered StreamHook.
     *
     * @param message the user message
     * @return this session for chaining
     */
    AgentSession chat(String message);

    /**
     * Send a message with tweaks.
     *
     * @param message the user message
     * @param tweaks node tweaks to apply
     * @return this session for chaining
     */
    AgentSession chat(String message, java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> tweaks);

    /**
     * Interrupt the current session execution.
     */
    void interrupt();

    /**
     * Check if the session is currently active (streaming or processing).
     */
    boolean isActive();

    /**
     * Register a StreamHook for this session.
     */
    AgentSession onStream(StreamHook hook);

    /**
     * Register a ToolExecutionHook for this session.
     */
    AgentSession onToolExecution(ToolExecutionHook hook);

    /**
     * Register a PermissionHook for this session.
     */
    AgentSession onPermission(PermissionHook hook);

    /**
     * Register a SessionHook for this session.
     */
    AgentSession onLifecycle(SessionHook hook);

    /**
     * Wait for the session to complete.
     * Blocks the calling thread until the session finishes or is interrupted.
     */
    void await() throws InterruptedException;
}
