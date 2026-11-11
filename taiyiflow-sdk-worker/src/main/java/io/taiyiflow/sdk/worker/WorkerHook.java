package io.taiyiflow.sdk.worker;

import io.taiyiflow.sdk.collaboration.CollaborationPlugin;

public interface WorkerHook {
    default void onReady(DigitalWorker worker) {
    }

    default void onTaskStart(CollaborationPlugin.TaskDispatch task) {
    }

    default void onTaskFinish(CollaborationPlugin.TaskDispatch task, TaskExecutionState state) {
    }

    default void onError(String stage, Throwable error) {
    }

    default void onStop() {
    }
}
