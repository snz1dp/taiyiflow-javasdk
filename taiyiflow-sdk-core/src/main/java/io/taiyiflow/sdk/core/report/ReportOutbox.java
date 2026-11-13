package io.taiyiflow.sdk.core.report;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Minimal final-result compensation store. It never contains an execution plan. */
public interface ReportOutbox {

    enum Type {
        CLIENT_TOOL_RESULT,
        CHECKPOINT_TOOL_RESULTS
    }

    enum Status {
        PENDING,
        REPORTING,
        DEAD_LETTER
    }

    Entry put(Entry entry) throws IOException;

    Optional<Entry> find(String id);

    List<Entry> list(Type type);

    List<Entry> listAll();

    Entry markReporting(String id) throws IOException;

    Entry markRetry(String id, String safeError, Duration backoff) throws IOException;

    Entry markDeadLetter(String id, String safeError) throws IOException;

    void delete(String id) throws IOException;

    Limits limits();

    boolean isPersistent();

    default List<Entry> forCheckpoint(String checkpointId) {
        return list(Type.CHECKPOINT_TOOL_RESULTS).stream()
                .filter(entry -> Objects.equals(checkpointId, entry.getCheckpointId()))
                .toList();
    }

    static Entry client(ObjectNode payload) {
        String runId = required(payload.path("run_id").asText(null), "run_id");
        String callId = required(payload.path("call_id").asText(null), "call_id");
        Entry entry = new Entry();
        entry.setType(Type.CLIENT_TOOL_RESULT);
        entry.setDedupeKey(runId + "\0" + callId);
        entry.setId(idFor(entry.getType(), entry.getDedupeKey()));
        entry.setFlowId(text(payload, "flow_id"));
        entry.setSessionId(text(payload, "session_id"));
        entry.setMessageId(text(payload, "message_id"));
        entry.setRunId(runId);
        entry.setCallId(callId);
        entry.setPayload(CanonicalJson.canonical(payload));
        entry.setCreatedAtEpochMillis(System.currentTimeMillis());
        entry.setStatus(Status.PENDING);
        return entry;
    }

    static Entry checkpoint(String flowId, String sessionId, String runId, String checkpointId,
                            int checkpointVersion, String submissionId, ArrayNode payload) {
        Entry entry = new Entry();
        entry.setType(Type.CHECKPOINT_TOOL_RESULTS);
        entry.setDedupeKey(required(checkpointId, "checkpointId") + "\0"
                + required(submissionId, "submissionId"));
        entry.setId(idFor(entry.getType(), entry.getDedupeKey()));
        entry.setFlowId(required(flowId, "flowId"));
        entry.setSessionId(required(sessionId, "sessionId"));
        entry.setRunId(required(runId, "runId"));
        entry.setCheckpointId(checkpointId);
        entry.setCheckpointVersion(checkpointVersion);
        entry.setSubmissionId(submissionId);
        entry.setSubmissionHash(CanonicalJson.sha256(payload));
        entry.setPayload(CanonicalJson.canonical(payload));
        entry.setCreatedAtEpochMillis(System.currentTimeMillis());
        entry.setStatus(Status.PENDING);
        return entry;
    }

    static String newSubmissionId(String checkpointId, ArrayNode canonicalPayload) {
        return UUID.nameUUIDFromBytes((checkpointId + "\0" + CanonicalJson.stringify(canonicalPayload))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    static String idFor(Type type, String dedupeKey) {
        return type.name().toLowerCase(java.util.Locale.ROOT) + "-" + CanonicalJson.sha256(dedupeKey);
    }

    private static String text(ObjectNode payload, String field) {
        return payload.path(field).asText(null);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required for a report outbox entry");
        }
        return value;
    }

    /** Serializable minimal record. All mutators are intended for storage implementations/Jackson. */
    final class Entry {
        private String id;
        private Type type;
        private String dedupeKey;
        private Status status;
        private String flowId;
        private String sessionId;
        private String messageId;
        private String runId;
        private String callId;
        private String checkpointId;
        private int checkpointVersion;
        private String submissionId;
        private String submissionHash;
        private JsonNode payload;
        private long createdAtEpochMillis;
        private int retryCount;
        private long nextAttemptAtEpochMillis;
        private String lastError;

        public Entry copy() {
            Entry copy = new Entry();
            copy.id = id;
            copy.type = type;
            copy.dedupeKey = dedupeKey;
            copy.status = status;
            copy.flowId = flowId;
            copy.sessionId = sessionId;
            copy.messageId = messageId;
            copy.runId = runId;
            copy.callId = callId;
            copy.checkpointId = checkpointId;
            copy.checkpointVersion = checkpointVersion;
            copy.submissionId = submissionId;
            copy.submissionHash = submissionHash;
            copy.payload = payload == null ? null : payload.deepCopy();
            copy.createdAtEpochMillis = createdAtEpochMillis;
            copy.retryCount = retryCount;
            copy.nextAttemptAtEpochMillis = nextAttemptAtEpochMillis;
            copy.lastError = lastError;
            return copy;
        }

        @JsonIgnore
        public boolean isDue() {
            return status == Status.PENDING && nextAttemptAtEpochMillis <= System.currentTimeMillis();
        }

        public String getId() { return id; }
        public void setId(String value) { id = value; }
        public Type getType() { return type; }
        public void setType(Type value) { type = value; }
        public String getDedupeKey() { return dedupeKey; }
        public void setDedupeKey(String value) { dedupeKey = value; }
        public Status getStatus() { return status; }
        public void setStatus(Status value) { status = value; }
        public String getFlowId() { return flowId; }
        public void setFlowId(String value) { flowId = value; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String value) { sessionId = value; }
        public String getMessageId() { return messageId; }
        public void setMessageId(String value) { messageId = value; }
        public String getRunId() { return runId; }
        public void setRunId(String value) { runId = value; }
        public String getCallId() { return callId; }
        public void setCallId(String value) { callId = value; }
        public String getCheckpointId() { return checkpointId; }
        public void setCheckpointId(String value) { checkpointId = value; }
        public int getCheckpointVersion() { return checkpointVersion; }
        public void setCheckpointVersion(int value) { checkpointVersion = value; }
        public String getSubmissionId() { return submissionId; }
        public void setSubmissionId(String value) { submissionId = value; }
        public String getSubmissionHash() { return submissionHash; }
        public void setSubmissionHash(String value) { submissionHash = value; }
        public JsonNode getPayload() { return payload == null ? null : payload.deepCopy(); }
        public void setPayload(JsonNode value) { payload = value == null ? null : value.deepCopy(); }
        public long getCreatedAtEpochMillis() { return createdAtEpochMillis; }
        public void setCreatedAtEpochMillis(long value) { createdAtEpochMillis = value; }
        public int getRetryCount() { return retryCount; }
        public void setRetryCount(int value) { retryCount = value; }
        public long getNextAttemptAtEpochMillis() { return nextAttemptAtEpochMillis; }
        public void setNextAttemptAtEpochMillis(long value) { nextAttemptAtEpochMillis = value; }
        public String getLastError() { return lastError; }
        public void setLastError(String value) { lastError = value; }
    }

    record Limits(int maxEntries, long maxEntryBytes, long maxTotalBytes, int maxRetries) {
        public static final Limits DEFAULT = new Limits(1000, 1024 * 1024, 16 * 1024 * 1024, 32);

        public Limits {
            if (maxEntries <= 0 || maxEntryBytes <= 0 || maxTotalBytes <= 0 || maxRetries <= 0) {
                throw new IllegalArgumentException("Outbox limits and retry budget must be positive");
            }
            if (maxEntryBytes > maxTotalBytes) {
                throw new IllegalArgumentException("Outbox entry limit must not exceed total limit");
            }
        }

        public static Limits of(int maxEntries, long maxEntryBytes, long maxTotalBytes, int maxRetries) {
            return new Limits(maxEntries, maxEntryBytes, maxTotalBytes, maxRetries);
        }
    }

    final class ReportConflictException extends IOException {
        public ReportConflictException(String id) {
            super("Conflicting final result for outbox entry " + id);
        }
    }
}
