package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface TaskProgress {
    void report(JsonNode progress) throws Exception;
}
