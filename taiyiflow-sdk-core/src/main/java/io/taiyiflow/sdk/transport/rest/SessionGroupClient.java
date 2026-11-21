package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Client session-group management ({@code /agents/session-groups}). */
public class SessionGroupClient {
    private static final ObjectMapper mapper = new ObjectMapper();
    private final RestClient rest;

    public SessionGroupClient(RestClient rest) {
        this.rest = rest;
    }

    public JsonNode list(String flowId) throws Exception {
        return list(flowId, null, null, null, 0, 100);
    }

    public JsonNode list(String flowId, String clientId, String keyword, String orderBy,
                         int offset, int limit) throws Exception {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("flow_id", flowId);
        query.put("client_id", clientId);
        query.put("keyword", keyword);
        query.put("order_by", orderBy);
        query.put("offset", offset);
        query.put("limit", limit);
        return rest.get(RestClient.appendQuery(base(), query));
    }

    public JsonNode create(String flowId, String name) throws Exception {
        return create(mapper.createObjectNode().put("flow_id", flowId).put("name", name));
    }

    public JsonNode create(JsonNode body) throws Exception {
        return rest.post(base(), body);
    }

    public JsonNode get(String groupId) throws Exception {
        return rest.get(groupPath(groupId));
    }

    public JsonNode update(String groupId, JsonNode body) throws Exception {
        return rest.post(groupPath(groupId) + "/update", body);
    }

    public void delete(String groupId) throws Exception {
        rest.delete(groupPath(groupId));
    }

    /** Move sessions into a group; a null groupId removes them from any group. */
    public JsonNode moveSessions(Collection<String> sessionIds, String groupId) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.set("session_ids", mapper.valueToTree(sessionIds));
        if (groupId != null) {
            body.put("group_id", groupId);
        } else {
            body.putNull("group_id");
        }
        return rest.post(base() + "/sessions", body);
    }

    private static String base() {
        return "/api/v1/agents/session-groups";
    }

    private static String groupPath(String groupId) {
        return base() + "/" + RestClient.pathSegment(groupId);
    }
}