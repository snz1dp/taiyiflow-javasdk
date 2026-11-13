package io.taiyiflow.sdk.core.dispatch;

import java.util.Objects;

/** A predictable scheduling or execution failure that must be returned as a tool error. */
public final class ToolExecutionException extends Exception {

    public enum Reason {
        QUEUE_FULL,
        EXECUTION_TIMEOUT,
        CANCELLED,
        CLOSED,
        EXECUTION_FAILED
    }

    private final Reason reason;

    public ToolExecutionException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public ToolExecutionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason getReason() {
        return reason;
    }
}
