package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-authoritative tool call records for one message
 * (GET /api/v1/agents/flows/{flow}/sessions/{session}/messages/{message}/tool-calls).
 */
public class ToolCallRecordClient {
    private static final int MAX_PAGE_SIZE = 1000;

    private final RestClient rest;

    public ToolCallRecordClient(RestClient rest) {
        this.rest = rest;
    }

    public record Query(String runId, String toolType, Boolean viaClient, String orderBy, int offset, int limit) {
        public Query {
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be >= 0");
            }
            if (limit < 1 || limit > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("limit must be within 1.." + MAX_PAGE_SIZE);
            }
        }

        public static Query defaults() {
            return new Query(null, null, null, null, 0, 100);
        }

        public Query withRunId(String value) {
            return new Query(value, toolType, viaClient, orderBy, offset, limit);
        }

        public Query withViaClient(Boolean value) {
            return new Query(runId, toolType, value, orderBy, offset, limit);
        }

        public Query withOrderBy(String value) {
            return new Query(runId, toolType, viaClient, value, offset, limit);
        }

        public Query withOffset(int value) {
            return new Query(runId, toolType, viaClient, orderBy, value, limit);
        }
    }

    /** Key fields are typed; the full server record stays available via {@link #raw()} for forward compatibility. */
    public record Record(String id, String callId, Integer callIndex, String callType, String toolType,
                         String toolId, String toolName, JsonNode arguments, String flowId, String sessionId,
                         String messageId, String runId, boolean viaClient, String result, String status,
                         String action, String errorMessage, String createdAt, String finishedAt,
                         Boolean waitForResponse, JsonNode raw) {

        /** Backend terminal states proving the platform already received the final result. */
        public boolean resultReceived() {
            return "success".equals(status) || "error".equals(status);
        }

        /** User-pause freeze: late results are rejected with tool_call_paused and must be discarded. */
        public boolean frozenPaused() {
            return "client_paused".equals(status);
        }

        /** Offline freeze: late real results may still compensate the record. */
        public boolean frozenOffline() {
            return "client_offline".equals(status);
        }

        public static Record from(JsonNode node) {
            return new Record(
                    text(node, "id"), text(node, "call_id"),
                    node.hasNonNull("call_index") ? node.get("call_index").asInt() : null,
                    text(node, "call_type"), text(node, "tool_type"), text(node, "tool_id"),
                    text(node, "tool_name"), node.get("arguments"),
                    text(node, "flow_id"), text(node, "session_id"), text(node, "message_id"),
                    text(node, "run_id"), node.path("via_client").asBoolean(false),
                    text(node, "result"), text(node, "status"), text(node, "action"),
                    text(node, "error_message"), text(node, "created_at"), text(node, "finished_at"),
                    node.hasNonNull("wait_for_response") ? node.get("wait_for_response").asBoolean() : null,
                    node);
        }

        private static String text(JsonNode node, String field) {
            return node.hasNonNull(field) ? node.get(field).asText() : null;
        }
    }

    public record Page(int total, int offset, List<Record> data) {
    }

    public Page list(String flowId, String sessionId, String messageId, Query query) throws Exception {
        Query effective = query == null ? Query.defaults() : query;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("run_id", effective.runId());
        params.put("tool_type", effective.toolType());
        params.put("via_client", effective.viaClient());
        params.put("order_by", effective.orderBy());
        params.put("offset", effective.offset());
        params.put("limit", effective.limit());
        JsonNode response = rest.get(RestClient.appendQuery(basePath(flowId, sessionId, messageId), params));
        List<Record> data = new ArrayList<>();
        for (JsonNode node : response.path("data")) {
            data.add(Record.from(node));
        }
        return new Page(response.path("total").asInt(0), response.path("offset").asInt(0), List.copyOf(data));
    }

    /** All client-side records of one run keyed by call_id, paginated in occurrence order. */
    public Map<String, Record> byCallId(String flowId, String sessionId, String messageId,
                                        String runId, Boolean viaClient) throws Exception {
        Map<String, Record> byCallId = new LinkedHashMap<>();
        int offset = 0;
        while (true) {
            Page page = list(flowId, sessionId, messageId,
                    new Query(runId, null, viaClient, "created_at asc", offset, MAX_PAGE_SIZE));
            for (Record record : page.data()) {
                if (record.callId() != null) {
                    byCallId.putIfAbsent(record.callId(), record);
                }
            }
            offset += page.data().size();
            if (page.data().isEmpty() || offset >= page.total()) {
                return byCallId;
            }
        }
    }

    private static String basePath(String flowId, String sessionId, String messageId) {
        return "/api/v1/agents/flows/" + RestClient.pathSegment(flowId)
                + "/sessions/" + RestClient.pathSegment(sessionId)
                + "/messages/" + RestClient.pathSegment(messageId) + "/tool-calls";
    }
}