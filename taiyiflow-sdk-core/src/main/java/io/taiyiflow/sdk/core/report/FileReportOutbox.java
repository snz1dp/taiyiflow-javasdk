package io.taiyiflow.sdk.core.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** One-JSON-file-per-record store with atomic replacement and corruption quarantine. */
public final class FileReportOutbox implements ReportOutbox {
    private static final Logger log = LoggerFactory.getLogger(FileReportOutbox.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path directory;
    private final Path quarantine;
    private final Limits limits;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private long totalBytes;

    public FileReportOutbox(Path stateDirectory) throws IOException {
        this(stateDirectory, Limits.DEFAULT);
    }

    public FileReportOutbox(Path stateDirectory, Limits limits) throws IOException {
        if (stateDirectory == null) {
            throw new IllegalArgumentException("stateDirectory is required for a persistent outbox");
        }
        this.directory = stateDirectory.toAbsolutePath().normalize().resolve("report-outbox");
        this.quarantine = directory.resolve("quarantine");
        this.limits = limits;
        createPrivateDirectories();
        load();
    }

    @Override
    public synchronized Entry put(Entry proposed) throws IOException {
        Entry value = InMemoryReportOutbox.validated(proposed);
        Entry existing = entries.get(value.getId());
        if (existing != null) {
            if (InMemoryReportOutbox.sameFinalResult(existing, value)) {
                return existing.copy();
            }
            existing.setStatus(Status.DEAD_LETTER);
            existing.setLastError("result-conflict");
            persist(existing);
            throw new ReportConflictException(value.getId());
        }
        long size = InMemoryReportOutbox.entrySize(value);
        if (size > limits.maxEntryBytes()) {
            throw new IOException("Outbox entry exceeds max-entry-bytes");
        }
        if (entries.size() >= limits.maxEntries() || totalBytes + size > limits.maxTotalBytes()) {
            throw new IOException("Outbox bounds exceeded");
        }
        persist(value);
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
    public synchronized Entry markReporting(String id) throws IOException {
        Entry entry = required(id);
        if (entry.getStatus() != Status.DEAD_LETTER) {
            entry.setStatus(Status.REPORTING);
            update(entry);
        }
        return entry.copy();
    }

    @Override
    public synchronized Entry markRetry(String id, String safeError, Duration backoff) throws IOException {
        Entry entry = required(id);
        if (entry.getStatus() != Status.DEAD_LETTER) {
            entry.setRetryCount(entry.getRetryCount() + 1);
            entry.setLastError(InMemoryReportOutbox.safeError(safeError));
            if (entry.getRetryCount() >= limits.maxRetries()) {
                entry.setStatus(Status.DEAD_LETTER);
                entry.setLastError("retry-budget-exhausted");
                entry.setNextAttemptAtEpochMillis(0);
            } else {
                entry.setStatus(Status.PENDING);
                entry.setNextAttemptAtEpochMillis(System.currentTimeMillis() + Math.max(0, backoff.toMillis()));
            }
            update(entry);
        }
        return entry.copy();
    }

    @Override
    public synchronized Entry markDeadLetter(String id, String safeError) throws IOException {
        Entry entry = required(id);
        entry.setStatus(Status.DEAD_LETTER);
        entry.setLastError(InMemoryReportOutbox.safeError(safeError));
        entry.setNextAttemptAtEpochMillis(0);
        update(entry);
        return entry.copy();
    }

    @Override
    public synchronized void delete(String id) throws IOException {
        Entry existing = entries.get(id);
        if (existing == null) {
            return;
        }
        Files.deleteIfExists(path(existing));
        entries.remove(id);
        totalBytes -= InMemoryReportOutbox.entrySize(existing);
    }

    @Override
    public Limits limits() {
        return limits;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    public Path directory() {
        return directory;
    }

    public Path quarantineDirectory() {
        return quarantine;
    }

    private void update(Entry entry) throws IOException {
        persist(entry);
        entries.put(entry.getId(), entry);
        recalculateSize();
    }

    private Entry required(String id) {
        Entry entry = entries.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown outbox entry " + id);
        }
        return entry.copy();
    }

    private void persist(Entry entry) throws IOException {
        Path target = path(entry);
        Path temporary = directory.resolve(target.getFileName() + ".tmp-" + UUID.randomUUID());
        byte[] bytes = MAPPER.writeValueAsBytes(entry);
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            setPrivateFilePermissions(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Atomic report outbox replacement is unsupported", exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void load() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            stream.forEach(files::add);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read report outbox directory", exception);
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path file : files) {
            try {
                if (Files.size(file) > limits.maxEntryBytes()) {
                    quarantine(file, "oversized");
                    continue;
                }
                Entry entry = InMemoryReportOutbox.validated(MAPPER.readValue(Files.readAllBytes(file), Entry.class));
                if (!file.equals(path(entry))) {
                    quarantine(file, "identity-mismatch");
                    continue;
                }
                if (entry.getStatus() == Status.REPORTING) {
                    // A crash cannot prove whether the platform accepted this attempt.
                    entry.setStatus(Status.PENDING);
                    entry.setLastError("recovered-interrupted-report");
                    persist(entry);
                }
                if (entries.size() >= limits.maxEntries()
                        || totalBytes + InMemoryReportOutbox.entrySize(entry) > limits.maxTotalBytes()) {
                    quarantine(file, "bounds-exceeded");
                    continue;
                }
                entries.put(entry.getId(), entry);
                totalBytes += InMemoryReportOutbox.entrySize(entry);
            } catch (Exception exception) {
                quarantine(file, "corrupt");
            }
        }
    }

    private void quarantine(Path file, String reason) {
        try {
            Path target = quarantine.resolve(file.getFileName() + "." + reason + "-" + UUID.randomUUID());
            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
            log.warn("Quarantined report outbox file {}: {}", file.getFileName(), reason);
        } catch (Exception exception) {
            log.error("Unable to quarantine report outbox file {}", file.getFileName(), exception);
        }
    }

    private void recalculateSize() {
        totalBytes = entries.values().stream().mapToLong(InMemoryReportOutbox::entrySize).sum();
    }

    private Path path(Entry entry) {
        return directory.resolve(entry.getId() + ".json");
    }

    private void createPrivateDirectories() throws IOException {
        Files.createDirectories(directory);
        Files.createDirectories(quarantine);
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(quarantine, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems use their native ACLs.
        }
    }

    private static void setPrivateFilePermissions(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems use their native ACLs.
        }
    }
}
