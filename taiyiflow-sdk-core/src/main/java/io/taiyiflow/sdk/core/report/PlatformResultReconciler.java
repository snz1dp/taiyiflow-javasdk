package io.taiyiflow.sdk.core.report;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.transport.rest.ToolCallRecordClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Platform-authoritative reconciliation of compensation entries against received tool call records.
 * It only classifies; it never replays a result whose platform state is unknown.
 */
public final class PlatformResultReconciler {
    private static final Logger log = LoggerFactory.getLogger(PlatformResultReconciler.class);

    public enum Verdict {
        /** Every call already has a terminal success/error record on the platform. */
        RECEIVED,
        /** Every call is received or pause-frozen; the entry must be discarded, not replayed. */
        DISCARD_PAUSED,
        /** Missing records, non-terminal states or query failures: keep the entry for manual reconciliation. */
        UNKNOWN
    }

    private final ToolCallRecordClient records;

    public PlatformResultReconciler(ToolCallRecordClient records) {
        this.records = records;
    }

    public Verdict classify(String flowId, String sessionId, String runId,
                            Map<String, Set<String>> callIdsByMessage) {
        if (callIdsByMessage.isEmpty()) {
            return Verdict.UNKNOWN;
        }
        boolean anyPaused = false;
        for (Map.Entry<String, Set<String>> group : callIdsByMessage.entrySet()) {
            String messageId = group.getKey();
            if (messageId == null || messageId.isBlank() || group.getValue().isEmpty()) {
                return Verdict.UNKNOWN;
            }
            Map<String, ToolCallRecordClient.Record> byCallId;
            try {
                byCallId = records.byCallId(flowId, sessionId, messageId, runId, true);
            } catch (Exception exception) {
                log.debug("Tool call record query failed for message {}; keeping entry unresolved", messageId);
                return Verdict.UNKNOWN;
            }
            for (String callId : group.getValue()) {
                ToolCallRecordClient.Record record = byCallId.get(callId);
                if (record == null) {
                    return Verdict.UNKNOWN;
                }
                if (record.resultReceived()) {
                    continue;
                }
                if (record.frozenPaused()) {
                    anyPaused = true;
                    continue;
                }
                return Verdict.UNKNOWN;
            }
        }
        return anyPaused ? Verdict.DISCARD_PAUSED : Verdict.RECEIVED;
    }

    /** Classify one CLIENT_TOOL_RESULT payload. */
    public Verdict classifyClientPayload(JsonNode payload) {
        String flowId = payload.path("flow_id").asText(null);
        String sessionId = payload.path("session_id").asText(null);
        String runId = payload.path("run_id").asText(null);
        String messageId = payload.path("message_id").asText(null);
        String callId = payload.path("call_id").asText(null);
        if (flowId == null || sessionId == null || runId == null || messageId == null || callId == null) {
            return Verdict.UNKNOWN;
        }
        return classify(flowId, sessionId, runId, Map.of(messageId, Set.of(callId)));
    }

    /** Classify a CHECKPOINT_TOOL_RESULTS payload (array of canonical tool results). */
    public Verdict classifyCheckpointPayload(JsonNode results, String flowId, String sessionId, String runId) {
        return classify(flowId, sessionId, runId, groupByMessage(results));
    }

    /** Group only the given call ids of a checkpoint payload by their message_id. */
    public Verdict classifyCheckpointCalls(JsonNode results, String flowId, String sessionId, String runId,
                                           Set<String> callIds) {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (JsonNode result : results) {
            String callId = result.path("call_id").asText(null);
            if (callId == null || !callIds.contains(callId)) {
                continue;
            }
            String messageId = result.path("message_id").asText(null);
            if (messageId == null) {
                return Verdict.UNKNOWN;
            }
            grouped.computeIfAbsent(messageId, key -> new LinkedHashSet<>()).add(callId);
        }
        if (grouped.values().stream().mapToInt(Set::size).sum() != callIds.size()) {
            return Verdict.UNKNOWN;
        }
        return classify(flowId, sessionId, runId, grouped);
    }

    private static Map<String, Set<String>> groupByMessage(JsonNode results) {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (JsonNode result : results) {
            String messageId = result.path("message_id").asText(null);
            String callId = result.path("call_id").asText(null);
            if (messageId == null || callId == null) {
                return Map.of();
            }
            grouped.computeIfAbsent(messageId, key -> new LinkedHashSet<>()).add(callId);
        }
        return grouped;
    }
}