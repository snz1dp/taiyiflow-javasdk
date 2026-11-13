package io.taiyiflow.sdk.worker;

import java.util.Objects;

public record WorkerRegistrationResult(WorkerIdentity identity) {
    public WorkerRegistrationResult {
        Objects.requireNonNull(identity, "identity");
    }

    public static WorkerRegistrationResult of(WorkerIdentity identity) {
        return new WorkerRegistrationResult(identity);
    }
}
