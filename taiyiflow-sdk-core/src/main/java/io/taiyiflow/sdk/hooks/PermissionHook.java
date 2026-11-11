package io.taiyiflow.sdk.hooks;

import io.taiyiflow.sdk.transport.envelope.ToolCall;

import java.util.concurrent.CompletableFuture;

/**
 * Hook for approving sensitive operations.
 * Implement this to control whether tool calls are allowed.
 */
public interface PermissionHook {

    enum Decision {
        ALLOW,
        DENY,
        ASK
    }

    /**
     * Called when a tool call requires permission approval.
     *
     * @param sessionId the session identifier
     * @param toolCall the tool call requesting permission
     * @return the asynchronous permission decision
     */
    CompletableFuture<Decision> onPermission(String sessionId, ToolCall toolCall);
}
