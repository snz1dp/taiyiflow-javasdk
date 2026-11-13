package io.taiyiflow.sdk.core.subtask;

import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;

public interface LocalSubtaskExecutor {
    boolean submit(SubtaskTask task, ToolExecutionContext.Cancellation cancellation) throws Exception;

    default void cancel(SubtaskTask task) throws Exception {
    }
}
