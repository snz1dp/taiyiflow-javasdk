package io.taiyiflow.sdk.worker;

public final class EnvironmentSecretProvider implements SecretProvider {
    @Override
    public String get(String name) {
        return name == null ? null : System.getenv(name);
    }
}
