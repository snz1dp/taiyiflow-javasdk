package io.taiyiflow.sdk.transport.envelope;

/**
 * WebSocket close codes defined by the backend protocol.
 */
public final class CloseCodes {

    private CloseCodes() {
    }

    /** Normal closure */
    public static final int NORMAL = 1000;

    /** Authentication failed */
    public static final int AUTH_FAILED = 4001;

    /** Flow not found */
    public static final int FLOW_NOT_FOUND = 4002;

    /** Invalid request */
    public static final int INVALID_REQUEST = 4003;

    /** Rate limited */
    public static final int RATE_LIMITED = 4004;

    /** Internal server error */
    public static final int INTERNAL_ERROR = 4009;

    public static String describe(int code) {
        return switch (code) {
            case NORMAL -> "Normal closure";
            case AUTH_FAILED -> "Authentication failed";
            case FLOW_NOT_FOUND -> "Flow not found";
            case INVALID_REQUEST -> "Invalid request";
            case RATE_LIMITED -> "Rate limited";
            case INTERNAL_ERROR -> "Internal server error";
            default -> "Unknown close code: " + code;
        };
    }

    public static boolean isError(int code) {
        return code >= 4001;
    }
}
