package io.taiyiflow.sdk.worker;

import io.taiyiflow.sdk.collaboration.CollaborationPlugin;

@FunctionalInterface
public interface WorkerTaskHandler {
    Object execute(CollaborationPlugin.TaskDispatch task, TaskProgress progress) throws Exception;
}
