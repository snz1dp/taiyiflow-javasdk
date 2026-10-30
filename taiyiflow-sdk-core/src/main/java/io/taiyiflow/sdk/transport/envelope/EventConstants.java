package io.taiyiflow.sdk.transport.envelope;

/**
 * Event type constants matching the backend protocol.
 */
public final class EventConstants {

    private EventConstants() {
    }

    /** Message chunk event */
    public static final String MESSAGE = "message";

    /** Heartbeat/keepalive event */
    public static final String HEARTBEAT = "heartbeat";

    /** Error event */
    public static final String ERROR = "error";

    /** Stream close event */
    public static final String CLOSE = "close";

    /** Custom event (tool calls, etc.) */
    public static final String CUSTOM = "custom";
}
