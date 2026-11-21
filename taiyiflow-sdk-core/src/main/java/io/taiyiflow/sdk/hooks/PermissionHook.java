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

    /** Decision with an optional model-facing reason (e.g. a dangerous-command deny message). */
    record PermissionResult(Decision decision, String reason) {
    }

    /**
     * Called when a tool call requires permission approval.
     *
     * @param sessionId the session identifier
     * @param toolCall the tool call requesting permission
     * @return the asynchronous permission decision
     */
    CompletableFuture<Decision> onPermission(String sessionId, ToolCall toolCall);

    /**
     * Evaluation entry point used by the dispatcher; hooks may override it to attach a reason.
     * The default delegates to {@link #onPermission(String, ToolCall)} without a reason.
     */
    default CompletableFuture<PermissionResult> evaluate(String sessionId, ToolCall toolCall) {
        return onPermission(sessionId, toolCall).thenApply(decision -> new PermissionResult(decision, null));
    }
}
