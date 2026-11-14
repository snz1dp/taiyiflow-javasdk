package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class JsonNodes {
    private JsonNodes() {
    }

    public static Stream<JsonNode> stream(JsonNode node) {
        return node == null ? Stream.empty() : StreamSupport.stream(node.spliterator(), false);
    }
}
