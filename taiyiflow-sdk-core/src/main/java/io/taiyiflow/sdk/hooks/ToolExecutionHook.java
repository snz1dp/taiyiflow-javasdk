package io.taiyiflow.sdk.hooks;

import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;

/**
 * Hook for intercepting tool execution.
 * Implement this to observe, modify, or take over tool calls.
 */
public interface ToolExecutionHook {

    /**
     * Called before a tool is executed.
     * Can be used to modify the tool call or deny execution.
     *
     * @param sessionId the session identifier
     * @param toolCall the tool call about to be executed
     * @return the (possibly modified) tool call, or null to cancel execution
     */
    default ToolCall before(String sessionId, ToolCall toolCall) {
        return toolCall;
    }

    /**
     * Called after a tool has been executed.
     * Can be used to observe or modify the result.
     *
     * @param sessionId the session identifier
     * @param toolCall the tool call that was executed
     * @param result the tool result
     * @return the (possibly modified) tool result
     */
    default ToolResult after(String sessionId, ToolCall toolCall, ToolResult result) {
        return result;
    }

    /**
     * Called to take over tool execution.
     * If this returns a non-null result, the built-in plugin execution is skipped.
     *
     * @param sessionId the session identifier
     * @param toolCall the tool call to execute
     * @return the tool result if taking over, or null to let the built-in plugin handle it
     */
    default ToolResult takeover(String sessionId, ToolCall toolCall) {
        return null;
    }
}
