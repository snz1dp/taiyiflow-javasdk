package io.taiyiflow.sdk.core.state;

/**
 * Session execution state machine.
 */
public enum SessionState {
    /** Session created but not started */
    IDLE,
    /** Connecting to backend */
    CONNECTING,
    /** Streaming response */
    STREAMING,
    /** Executing tool calls */
    TOOL_CALLING,
    /** Session completed successfully */
    DONE,
    /** Session was interrupted */
    INTERRUPTED,
    /** Session failed with error */
    ERROR
}
