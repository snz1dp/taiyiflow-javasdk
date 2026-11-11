package io.taiyiflow.sdk.worker;

import java.util.Set;

@FunctionalInterface
public interface WorkerIdentityValidator {
    void validate(WorkerIdentity identity, WorkerRole role, Set<Ability> abilities) throws Exception;
}
