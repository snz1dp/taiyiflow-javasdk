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

    /** Custom event */
    public static final String CUSTOM = "custom";

    public static final String BYTES = "bytes";

    public static final String TOOL_CALL = "tool_call";

    public static final String FILE_WRITTEN = "file_written";

    public static final String CLIENT_TOOL = "client_tool";

    public static final String MCP_TOOL = "mcp_tool";

    public static final String BUILT_IN = "built_in";
}
