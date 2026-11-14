package io.taiyiflow.sdk.hooks;

/**
 * Hook for observing session lifecycle events.
 */
public interface SessionHook {

    /**
     * Called when a session starts.
     *
     * @param sessionId the session identifier
     */
    default void onStart(String sessionId) {
    }

    /**
     * Called when a session finishes normally.
     *
     * @param sessionId the session identifier
     */
    default void onFinish(String sessionId) {
    }

    /**
     * Called when a session is interrupted.
     *
     * @param sessionId the session identifier
     * @param reason the interruption reason
     */
    default void onInterrupt(String sessionId, String reason) {
    }

    default void onClose(String sessionId) {
    }
}
