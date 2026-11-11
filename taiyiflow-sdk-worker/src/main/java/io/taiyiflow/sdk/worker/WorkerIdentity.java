package io.taiyiflow.sdk.worker;

public record WorkerIdentity(String workerId, String displayName) {
    public WorkerIdentity {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }

    public static WorkerIdentity of(String workerId, String displayName) {
        return new WorkerIdentity(workerId, displayName);
    }
}
