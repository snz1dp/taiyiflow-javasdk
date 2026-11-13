package io.taiyiflow.sdk.worker;

@FunctionalInterface
public interface WorkerRegistrar {
    WorkerRegistrationResult register(WorkerRegistrationRequest request) throws Exception;
}
