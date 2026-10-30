package io.taiyiflow.sdk.hooks;

import io.taiyiflow.sdk.transport.envelope.StreamEvent;

/**
 * Hook for observing stream events.
 * Implement this to receive real-time message chunks and events from the agent.
 */
public interface StreamHook {

    /**
     * Called when a new chunk of the response message is received.
     *
     * @param sessionId the session identifier
     * @param chunk the message chunk (delta text)
     */
    default void onChunk(String sessionId, String chunk) {
    }

    /**
     * Called for any stream event (message, heartbeat, custom, etc.).
     *
     * @param sessionId the session identifier
     * @param event the stream event
     */
    default void onEvent(String sessionId, StreamEvent event) {
    }

    /**
     * Called when an error occurs during streaming.
     *
     * @param sessionId the session identifier
     * @param error the error that occurred
     */
    default void onError(String sessionId, Throwable error) {
    }

    /**
     * Called when the stream is closed (either normally or abnormally).
     *
     * @param sessionId the session identifier
     * @param reason the close reason, if available
     */
    default void onClose(String sessionId, String reason) {
    }
}
