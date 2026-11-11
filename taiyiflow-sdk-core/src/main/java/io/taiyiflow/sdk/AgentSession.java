package io.taiyiflow.sdk;

import io.taiyiflow.sdk.core.session.RunContext;
import io.taiyiflow.sdk.core.state.SessionState;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import io.taiyiflow.sdk.transport.envelope.AgentLoopControlResult;
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
     * Send a message with server-side file paths returned by FileClient.
     */
    AgentSession chat(String message, java.util.List<String> inputFiles);

    /**
     * Send a message with tweaks and server-side file paths.
     */
    AgentSession chat(String message, java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> tweaks,
                      java.util.List<String> inputFiles);

    /**
     * Request a server-side pause for the current run.
     */
    AgentLoopControlResult pause() throws Exception;

    /**
     * Cancel the current run on the server and close local streaming resources.
     */
    AgentLoopControlResult cancel() throws Exception;

    /**
     * Attach to the current server-side run without starting a new execution.
     */
    AgentSession attach();

    /**
     * Discover and resume the current resumable checkpoint.
     */
    AgentSession resume();

    /**
     * Resume a specific server-side checkpoint.
     */
    AgentSession resume(io.taiyiflow.sdk.transport.envelope.ResumableCheckpoint checkpoint);

    /**
     * Retry the last turn with a new run, using a continuation prompt after server persistence.
     */
    AgentSession retry();

    /**
     * Repeat the last input template with a new run.
     */
    AgentSession repeat();

    /**
     * Approve a pending local tool permission request.
     */
    boolean approve(String toolCallId);

    /**
     * Deny a pending local tool permission request.
     */
    boolean deny(String toolCallId, String reason);

    /**
     * Interrupt the current session execution locally without calling the server control API.
     */
    void interrupt();

    /**
     * Check if the session is currently active (streaming or processing).
     */
    boolean isActive();

    SessionState getState();

    RunContext getRunContext();

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

    /**
     * Wait for the current turn, returning false when the timeout elapses first.
     */
    boolean await(java.time.Duration timeout) throws InterruptedException;
}
