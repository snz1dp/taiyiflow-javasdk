package io.taiyiflow.sdk.transport.envelope;

/**
 * WebSocket close codes defined by the backend protocol.
 */
public final class CloseCodes {

    private CloseCodes() {
    }

    /** Normal closure */
    public static final int NORMAL = 1000;

    public static final int INVALID_PATH = 4000;

    public static final int AUTH_FAILED = 4001;

    public static final int INVALID_FIRST_FRAME = 4002;

    public static final int PERMISSION_DENIED = 4003;

    public static final int FLOW_UNAVAILABLE = 4004;

    public static final int SESSION_CONFLICT = 4009;

    public static String describe(int code) {
        return switch (code) {
            case NORMAL -> "Normal closure";
            case INVALID_PATH -> "Invalid WebSocket endpoint path";
            case AUTH_FAILED -> "Authentication failed";
            case INVALID_FIRST_FRAME -> "First frame timeout or invalid request";
            case PERMISSION_DENIED -> "Permission, quota, or model key denied";
            case FLOW_UNAVAILABLE -> "Flow not found or unavailable";
            case SESSION_CONFLICT -> "Session execution conflict";
            default -> "Unknown close code: " + code;
        };
    }

    public static boolean isError(int code) {
        return code >= 4000;
    }
}
