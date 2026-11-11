package io.taiyiflow.sdk;

import io.taiyiflow.sdk.transport.envelope.ChatRequest;

@FunctionalInterface
public interface FlowAdapter {
    void customize(ChatRequest request);
}
