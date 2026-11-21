package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Run/message intention records ({@code /agents/flows/{flow}/runs/{run}/intention} and
 * {@code .../messages/{message}/intention}). Used as a fallback after stream close when
 * relation_questions were not delivered in-band.
 */
public class IntentionClient {
    private final RestClient rest;

    public IntentionClient(RestClient rest) {
        this.rest = rest;
    }

    /** Intention for a run; null when the backend has no record (HTTP 404). */
    public JsonNode forRun(String flowId, String runId) throws Exception {
        return getOrNull("/api/v1/agents/flows/" + RestClient.pathSegment(flowId)
                + "/runs/" + RestClient.pathSegment(runId) + "/intention");
    }

    /** Intention for a message; null when the message is missing or has no record (HTTP 404). */
    public JsonNode forMessage(String flowId, String messageId) throws Exception {
        return getOrNull("/api/v1/agents/flows/" + RestClient.pathSegment(flowId)
                + "/messages/" + RestClient.pathSegment(messageId) + "/intention");
    }

    private JsonNode getOrNull(String path) throws Exception {
        try {
            return rest.get(path);
        } catch (RestClient.RestException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw e;
        }
    }
}