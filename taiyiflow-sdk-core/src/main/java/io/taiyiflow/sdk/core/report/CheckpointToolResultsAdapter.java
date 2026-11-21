package io.taiyiflow.sdk.core.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.transport.envelope.ResumableCheckpoint;
import io.taiyiflow.sdk.transport.rest.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checkpoint resume.tool_results persistence, reconciliation and conflict classification. */
public final class CheckpointToolResultsAdapter {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Prepared(ReportOutbox.Entry entry, ArrayNode toolResults, boolean persisted) {
        public boolean hasResults() {
            return toolResults != null && !toolResults.isEmpty();
        }
    }

    private final ReportOutbox outbox;

    public CheckpointToolResultsAdapter(ReportOutbox outbox) {
        this.outbox = outbox;
    }

    /** Build and durably save the exact first-frame payload before a resume stream is opened. */
    public Prepared prepare(ResumableCheckpoint checkpoint, String flowId, String sessionId,
                            Map<String, ObjectNode> pendingResults) throws IOException {
        List<ReportOutbox.Entry> existing = outbox.forCheckpoint(checkpoint.getId());
        if (existing.size() > 1) {
            for (ReportOutbox.Entry entry : existing) {
                outbox.markDeadLetter(entry.getId(), "multiple-checkpoint-submissions");
            }
            throw new ReportOutbox.ReportConflictException(checkpoint.getId());
        }
        if (!existing.isEmpty()) {
            return reconcile(existing.get(0), checkpoint, flowId, sessionId);
        }

        Set<String> outstanding = outstanding(checkpoint);
        List<String> callIds = new ArrayList<>(outstanding);
        java.util.Collections.sort(callIds);
        ArrayNode results = MAPPER.createArrayNode();
        for (String callId : callIds) {
            ObjectNode pending = pendingResults.get(callId);
            if (pending != null) {
                results.add(CanonicalJson.canonical(pending));
            }
        }
        if (results.isEmpty()) {
            return new Prepared(null, results, true);
        }
        ArrayNode canonical = CanonicalJson.canonical(results);
        String submissionId = ReportOutbox.newSubmissionId(checkpoint.getId(), canonical);
        ReportOutbox.Entry entry = ReportOutbox.checkpoint(flowId, sessionId, checkpoint.getRunId(),
                checkpoint.getId(), checkpoint.getVersion(), submissionId, canonical);
        try {
            entry = outbox.put(entry);
        } catch (ReportOutbox.ReportConflictException conflict) {
            throw conflict;
        }
        if (entry.getStatus() == ReportOutbox.Status.DEAD_LETTER) {
            throw new ReportOutbox.ReportConflictException(entry.getId());
        }
        return new Prepared(entry, (ArrayNode) entry.getPayload(), true);
    }

    /** Build the same deterministic submission for one direct attempt after a storage failure. */
    public Prepared direct(ResumableCheckpoint checkpoint, String flowId, String sessionId,
                           Map<String, ObjectNode> pendingResults) {
        List<String> callIds = new ArrayList<>(outstanding(checkpoint));
        java.util.Collections.sort(callIds);
        ArrayNode results = MAPPER.createArrayNode();
        for (String callId : callIds) {
            ObjectNode pending = pendingResults.get(callId);
            if (pending != null) {
                results.add(CanonicalJson.canonical(pending));
            }
        }
        if (results.isEmpty()) {
            return new Prepared(null, results, false);
        }
        ArrayNode canonical = CanonicalJson.canonical(results);
        String submissionId = ReportOutbox.newSubmissionId(checkpoint.getId(), canonical);
        return new Prepared(ReportOutbox.checkpoint(flowId, sessionId, checkpoint.getRunId(),
                checkpoint.getId(), checkpoint.getVersion(), submissionId, canonical), canonical, false);
    }

    /** Reconcile a record found after process restart; no tool dispatcher is involved. */
    public Prepared reconcile(ReportOutbox.Entry entry, ResumableCheckpoint checkpoint,
                              String flowId, String sessionId) throws IOException {
        return reconcile(entry, checkpoint, flowId, sessionId, null);
    }

    /**
     * Reconcile with an optional platform-authoritative tool call record query on top of the local
     * checkpoint comparison. Calls no longer waiting are only accepted when the platform proves the
     * result was received (or pause-frozen); unknown states stay dead-letter and are never replayed.
     */
    public Prepared reconcile(ReportOutbox.Entry entry, ResumableCheckpoint checkpoint,
                              String flowId, String sessionId,
                              io.taiyiflow.sdk.transport.rest.ToolCallRecordClient records) throws IOException {
        if (!flowId.equals(entry.getFlowId()) || !sessionId.equals(entry.getSessionId())
                || !java.util.Objects.equals(entry.getRunId(), checkpoint.getRunId())
                || !entry.getCheckpointId().equals(checkpoint.getId())) {
            return conflict(entry, "checkpoint-identity-conflict");
        }
        if (entry.getSubmissionId().equals(checkpoint.getLastSubmissionId())) {
            if (!entry.getSubmissionHash().equals(checkpoint.getLastSubmissionHash())) {
                return conflict(entry, "submission-hash-conflict");
            }
            // changed=false with the same id/hash is an acknowledged replay.
            outbox.delete(entry.getId());
            return new Prepared(null, MAPPER.createArrayNode(), true);
        }
        if (entry.getCheckpointVersion() != checkpoint.getVersion()) {
            return conflict(entry, "checkpoint-version-conflict");
        }
        String status = checkpoint.getStatus();
        if ("cancelled".equals(status) || "expired".equals(status)) {
            // The normal resume endpoint cannot safely prove archive-only cancellation semantics.
            return conflict(entry, "checkpoint-archive-requires-reconciliation");
        }
        Set<String> outstanding = outstanding(checkpoint);
        Set<String> notWaiting = new LinkedHashSet<>();
        for (JsonNode result : entry.getPayload()) {
            String callId = result.path("call_id").asText();
            if (!outstanding.contains(callId)) {
                notWaiting.add(callId);
            }
        }
        if (!notWaiting.isEmpty()) {
            if (records == null) {
                return conflict(entry, "call-not-waiting-or-uncertain");
            }
            PlatformResultReconciler.Verdict verdict = new PlatformResultReconciler(records)
                    .classifyCheckpointCalls(entry.getPayload(), flowId, sessionId, entry.getRunId(), notWaiting);
            if (verdict == PlatformResultReconciler.Verdict.UNKNOWN) {
                return conflict(entry, "call-not-waiting-or-uncertain");
            }
            if (outstanding.isEmpty()) {
                // Every submitted result is already terminal on the platform; nothing left to resume.
                outbox.delete(entry.getId());
                return new Prepared(null, MAPPER.createArrayNode(), true);
            }
            // Remaining outstanding calls still need the submission; identical replays are idempotent.
        }
        // Replay only the still-outstanding calls. Calls the platform already received or pause-froze
        // (the notWaiting set) must not be re-sent: re-submitting a pause-frozen result would violate
        // the discard-paused semantics. When nothing dropped out this is the full payload unchanged.
        ArrayNode replay = MAPPER.createArrayNode();
        for (JsonNode result : entry.getPayload()) {
            if (outstanding.contains(result.path("call_id").asText())) {
                replay.add(result);
            }
        }
        return new Prepared(entry, replay, true);
    }

    /** A valid stream event proves that the backend processed the resume first frame. */
    public void accepted(ReportOutbox.Entry entry) throws IOException {
        if (entry != null) {
            outbox.delete(entry.getId());
        }
    }

    /** Explicit adapter for backends/tests that expose the submission response as JSON. */
    public void submissionResponse(ReportOutbox.Entry entry, JsonNode response) throws IOException {
        String submissionId = response.path("submission_id").asText(entry.getSubmissionId());
        String hash = response.path("submission_hash").asText(entry.getSubmissionHash());
        if (!entry.getSubmissionId().equals(submissionId) || !entry.getSubmissionHash().equals(hash)) {
            conflict(entry, "submission-identity-conflict");
            return;
        }
        if (response.has("changed") && response.path("changed").isBoolean()) {
            // Both changed=true and idempotent changed=false are successful submissions.
            outbox.delete(entry.getId());
            return;
        }
        if (response.has("error") || response.has("detail")) {
            conflict(entry, "submission-conflict");
            return;
        }
        outbox.delete(entry.getId());
    }

    public ReportOutbox.Status failed(ReportOutbox.Entry entry, Exception exception) throws IOException {
        if (isDeterministic(exception)) {
            return outbox.markDeadLetter(entry.getId(), safeCode(exception)).getStatus();
        }
        return outbox.markRetry(entry.getId(), safeCode(exception), backoff(entry)).getStatus();
    }

    private Prepared conflict(ReportOutbox.Entry entry, String reason) throws IOException {
        outbox.markDeadLetter(entry.getId(), reason);
        throw new ReportOutbox.ReportConflictException(entry.getId());
    }

    private static Set<String> outstanding(ResumableCheckpoint checkpoint) {
        Set<String> ids = new LinkedHashSet<>();
        ids.addAll(checkpoint.getWaitingCallIds());
        ids.addAll(checkpoint.getUncertainCallIds());
        return ids;
    }

    private static boolean isDeterministic(Exception exception) {
        if (!(exception instanceof RestClient.RestException rest)) {
            return false;
        }
        int status = rest.getStatusCode();
        return status != 401 && status != 403 && status != 408 && status != 429 && status < 500;
    }

    private static String safeCode(Exception exception) {
        if (exception instanceof RestClient.RestException rest) {
            String code = rest.getDetailCode();
            if (code != null && code.matches("[A-Za-z0-9_.:-]{1,64}")) {
                return code;
            }
            return "http-" + rest.getStatusCode();
        }
        return "network-error";
    }

    private static Duration backoff(ReportOutbox.Entry entry) {
        long base = 500L;
        long multiplier = 1L << Math.min(20, entry.getRetryCount());
        return Duration.ofMillis(Math.min(Duration.ofMinutes(5).toMillis(), base * multiplier));
    }
}
