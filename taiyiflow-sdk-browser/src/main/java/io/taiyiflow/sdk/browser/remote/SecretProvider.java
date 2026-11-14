package io.taiyiflow.sdk.browser.remote;

@FunctionalInterface
public interface SecretProvider {
    String resolve(String name);
}
