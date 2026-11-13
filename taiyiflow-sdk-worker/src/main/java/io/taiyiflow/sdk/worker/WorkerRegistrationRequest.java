package io.taiyiflow.sdk.worker;

import java.util.Set;

public record WorkerRegistrationRequest(
        String displayName,
        WorkerRole role,
        Set<Ability> abilities) {
    public WorkerRegistrationRequest {
        abilities = abilities == null ? Set.of() : Set.copyOf(abilities);
    }
}
