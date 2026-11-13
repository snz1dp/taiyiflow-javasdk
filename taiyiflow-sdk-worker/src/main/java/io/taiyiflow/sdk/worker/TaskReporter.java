package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.collaboration.CollaborationPlugin;

public interface TaskReporter {
    void running(CollaborationPlugin.TaskDispatch task, JsonNode progress) throws Exception;

    void success(CollaborationPlugin.TaskDispatch task, Object result) throws Exception;

    void failure(CollaborationPlugin.TaskDispatch task, String error) throws Exception;

    default void cancelled(CollaborationPlugin.TaskDispatch task, String cancelRequestId) throws Exception {
        throw new UnsupportedOperationException("Task cancellation reporting is not supported");
    }
}
