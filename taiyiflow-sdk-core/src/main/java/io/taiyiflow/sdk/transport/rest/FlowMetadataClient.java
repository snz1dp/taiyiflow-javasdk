package io.taiyiflow.sdk.transport.rest;

import io.taiyiflow.sdk.transport.envelope.FlowMetadata;

public class FlowMetadataClient {
    private final RestClient rest;

    public FlowMetadataClient(RestClient rest) {
        this.rest = rest;
    }

    public FlowMetadata get(String flowIdOrName) throws Exception {
        if (flowIdOrName == null || flowIdOrName.isBlank()) {
            throw new IllegalArgumentException("flowIdOrName is required");
        }
        return new FlowMetadata(rest.get("/api/v1/run/" + RestClient.pathSegment(flowIdOrName) + "/metadata"));
    }
}
