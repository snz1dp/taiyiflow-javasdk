package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

public class ConfigClient {
    private final RestClient rest;

    public ConfigClient(RestClient rest) {
        this.rest = rest;
    }

    public JsonNode get() throws Exception {
        return rest.get("/api/v1/config");
    }
}
