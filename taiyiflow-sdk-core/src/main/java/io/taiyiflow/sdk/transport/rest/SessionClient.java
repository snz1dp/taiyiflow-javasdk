package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.Map;
import java.util.StringJoiner;

public class SessionClient {
    private static final ObjectMapper mapper = new ObjectMapper();
    private final RestClient rest;

    public SessionClient(RestClient rest) {
        this.rest = rest;
    }

    public String createSessionId() throws Exception {
        return rest.get("/api/v1/session_id").required("session_id").asText();
    }

    public JsonNode list(String flowId, int offset, int limit) throws Exception {
        return rest.get(RestClient.appendQuery(base(flowId), Map.of("offset", offset, "limit", limit)));
    }

    public JsonNode get(String flowId, String sessionId) throws Exception {
        return rest.get(sessionPath(flowId, sessionId));
    }

    public JsonNode rename(String flowId, String sessionId, String sessionName) throws Exception {
        return rest.post(sessionPath(flowId, sessionId),
                mapper.createObjectNode().put("session_name", sessionName));
    }

    public void delete(String flowId, String sessionId) throws Exception {
        rest.delete(sessionPath(flowId, sessionId));
    }

    public void deleteAll(String flowId, Collection<String> sessionIds) throws Exception {
        for (String sessionId : sessionIds) {
            delete(flowId, sessionId);
        }
    }

    public JsonNode messages(String flowId, String sessionId) throws Exception {
        return rest.get(sessionPath(flowId, sessionId) + "/messages");
    }

    public void deleteMessages(String flowId, String sessionId, Collection<String> messageIds) throws Exception {
        StringJoiner ids = new StringJoiner(",");
        messageIds.forEach(ids::add);
        rest.delete(RestClient.appendQuery(sessionPath(flowId, sessionId) + "/messages", Map.of("message_ids", ids.toString())));
    }

    private static String base(String flowId) {
        return "/api/v1/agents/flows/" + RestClient.pathSegment(flowId) + "/sessions";
    }

    private static String sessionPath(String flowId, String sessionId) {
        return base(flowId) + "/" + RestClient.pathSegment(sessionId);
    }
}
