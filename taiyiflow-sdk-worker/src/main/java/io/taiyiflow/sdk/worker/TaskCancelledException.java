package io.taiyiflow.sdk.worker;

final class TaskCancelledException extends Exception {
    TaskCancelledException() {
        super("Task was cancelled", null, false, false);
    }
}
