package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public class MemoryClient {
    private static final String BASE = "/api/v1/agents/memories";
    private final RestClient rest;

    public MemoryClient(RestClient rest) {
        this.rest = rest;
    }

    public JsonNode list(Map<String, ?> filters) throws Exception {
        return rest.get(RestClient.appendQuery(BASE, filters));
    }

    public JsonNode create(JsonNode memory) throws Exception {
        return rest.post(BASE, memory);
    }

    public JsonNode get(String memoryId) throws Exception {
        return rest.get(BASE + "/" + RestClient.pathSegment(memoryId));
    }

    public JsonNode forget(String memoryId) throws Exception {
        return rest.post(memoryPath(memoryId) + "/forget", null);
    }

    public JsonNode restore(String memoryId) throws Exception {
        return rest.post(memoryPath(memoryId) + "/restore", null);
    }

    private static String memoryPath(String memoryId) {
        return BASE + "/" + RestClient.pathSegment(memoryId);
    }
}
