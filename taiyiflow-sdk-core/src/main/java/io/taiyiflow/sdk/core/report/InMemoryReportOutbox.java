package io.taiyiflow.sdk.core.report;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Standalone default. Explicitly process-local and nonpersistent. */
public final class InMemoryReportOutbox implements ReportOutbox {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Limits limits;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long totalBytes;

    public InMemoryReportOutbox() {
        this(Limits.DEFAULT);
    }

    public InMemoryReportOutbox(Limits limits) {
        this.limits = limits;
    }

    @Override
    public synchronized Entry put(Entry proposed) throws IOException {
        Entry value = validated(proposed);
        Entry existing = entries.get(value.getId());
        if (existing != null) {
            if (sameFinalResult(existing, value)) {
                return existing.copy();
            }
            existing.setStatus(Status.DEAD_LETTER);
            existing.setLastError("result-conflict");
            throw new ReportConflictException(value.getId());
        }
        long size = entrySize(value);
        if (size > limits.maxEntryBytes()) {
            throw new IOException("Outbox entry exceeds max-entry-bytes");
        }
        if (entries.size() >= limits.maxEntries() || totalBytes + size > limits.maxTotalBytes()) {
            throw new IOException("Outbox bounds exceeded");
        }
        entries.put(value.getId(), value.copy());
        totalBytes += size;
        return value.copy();
    }

    @Override
    public synchronized Optional<Entry> find(String id) {
        return Optional.ofNullable(entries.get(id)).map(Entry::copy);
    }

    @Override
    public synchronized List<Entry> list(Type type) {
        return entries.values().stream()
                .filter(entry -> entry.getType() == type && entry.getStatus() != Status.DEAD_LETTER)
                .sorted(Comparator.comparingLong(Entry::getCreatedAtEpochMillis))
                .map(Entry::copy).toList();
    }

    @Override
    public synchronized List<Entry> listAll() {
        return entries.values().stream().map(Entry::copy).toList();
    }

    @Override
    public synchronized Entry markReporting(String id) {
        Entry entry = required(id);
        if (entry.getStatus() == Status.DEAD_LETTER) {
            return entry.copy();
        }
        entry.setStatus(Status.REPORTING);
        return entry.copy();
    }

    @Override
    public synchronized Entry markRetry(String id, String safeError, Duration backoff) {
        Entry entry = required(id);
        if (entry.getStatus() == Status.DEAD_LETTER) {
            return entry.copy();
        }
        entry.setRetryCount(entry.getRetryCount() + 1);
        entry.setLastError(safeError(safeError));
        if (entry.getRetryCount() >= limits.maxRetries()) {
            entry.setStatus(Status.DEAD_LETTER);
            entry.setLastError("retry-budget-exhausted");
        } else {
            entry.setStatus(Status.PENDING);
            entry.setNextAttemptAtEpochMillis(System.currentTimeMillis() + Math.max(0, backoff.toMillis()));
        }
        return entry.copy();
    }

    @Override
    public synchronized Entry markDeadLetter(String id, String safeError) {
        Entry entry = required(id);
        entry.setStatus(Status.DEAD_LETTER);
        entry.setLastError(safeError(safeError));
        entry.setNextAttemptAtEpochMillis(0);
        return entry.copy();
    }

    @Override
    public synchronized void delete(String id) {
        Entry removed = entries.remove(id);
        if (removed != null) {
            totalBytes -= entrySize(removed);
        }
    }

    @Override
    public Limits limits() {
        return limits;
    }

    @Override
    public boolean isPersistent() {
        return false;
    }

    static Entry validated(Entry proposed) throws IOException {
        if (proposed == null || proposed.getId() == null || proposed.getType() == null
                || proposed.getDedupeKey() == null || proposed.getPayload() == null
                || !proposed.getId().equals(ReportOutbox.idFor(proposed.getType(), proposed.getDedupeKey()))) {
            throw new IOException("Invalid outbox entry");
        }
        Entry value = proposed.copy();
        value.setPayload(CanonicalJson.canonical(value.getPayload()));
        if (value.getType() == Type.CHECKPOINT_TOOL_RESULTS) {
            String hash = CanonicalJson.sha256(value.getPayload());
            if (!hash.equals(value.getSubmissionHash())) {
                throw new IOException("Checkpoint submission hash mismatch");
            }
        }
        return value;
    }

    static boolean sameFinalResult(Entry first, Entry second) {
        return first.getType() == second.getType()
                && first.getDedupeKey().equals(second.getDedupeKey())
                && CanonicalJson.stringify(first.getPayload()).equals(CanonicalJson.stringify(second.getPayload()))
                && java.util.Objects.equals(first.getSubmissionHash(), second.getSubmissionHash());
    }

    static long entrySize(Entry entry) {
        try {
            return MAPPER.writeValueAsBytes(entry).length;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("Outbox entry is not serializable", exception);
        }
    }

    static String safeError(String value) {
        if (value == null || value.isBlank()) {
            return "report-failed";
        }
        // Callers pass machine codes, never server bodies or payload text.
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.substring(0, Math.min(128, normalized.length()));
    }

    private Entry required(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown outbox entry " + id);
        }
        return entry;
    }
}
