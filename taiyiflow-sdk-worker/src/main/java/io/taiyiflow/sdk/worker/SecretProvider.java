package io.taiyiflow.sdk.worker;

@FunctionalInterface
public interface SecretProvider {
    String get(String name);
}
