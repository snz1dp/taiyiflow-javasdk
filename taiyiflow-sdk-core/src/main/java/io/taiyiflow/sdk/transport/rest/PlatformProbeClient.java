package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Container liveness/readiness probes and server version. {@code /health} and
 * {@code /api/health_check} are unauthenticated and live outside the {@code /api/v1} prefix;
 * {@code /api/v1/version} requires login.
 */
public class PlatformProbeClient {
    private final RestClient rest;

    public PlatformProbeClient(RestClient rest) {
        this.rest = rest;
    }

    /** Liveness: {@code {"status":"ok"}}. */
    public JsonNode health() throws Exception {
        return rest.getUnauthenticated("/health");
    }

    /** Readiness with subsystem detail: {@code {status, storage, chat, db}}; HTTP 500 on failure. */
    public JsonNode healthCheck() throws Exception {
        return rest.getUnauthenticated("/api/health_check");
    }

    /** Server version/license summary: {@code {version, main_version, package, support, end}}. */
    public JsonNode version() throws Exception {
        return rest.get("/api/v1/version");
    }
}