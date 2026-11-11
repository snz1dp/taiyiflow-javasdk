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
    /** Pause requested but not yet frozen */
    PAUSING,
    /** Execution paused at a server-side checkpoint */
    PAUSED,
    /** Resume stream is being established */
    RESUMING,
    /** Session cancelled on the server */
    CANCELLED,
    /** Connection lost while execution may continue */
    DISCONNECTED,
    /** Confirmed execution loss is being recovered with a new run */
    RESTARTING,
    /** Session completed successfully */
    DONE,
    /** Session was interrupted */
    INTERRUPTED,
    /** Session failed with error */
    ERROR
}
