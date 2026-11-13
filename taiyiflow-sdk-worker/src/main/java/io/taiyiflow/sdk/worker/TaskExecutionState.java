package io.taiyiflow.sdk.worker;

public enum TaskExecutionState {
    QUEUED,
    RUNNING,
    PAUSED,
    SUCCESS,
    FAILED,
    CANCELLED,
    REPORT_UNKNOWN
}
