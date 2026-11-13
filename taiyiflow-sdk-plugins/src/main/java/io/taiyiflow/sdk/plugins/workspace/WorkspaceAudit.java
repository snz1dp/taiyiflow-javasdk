package io.taiyiflow.sdk.plugins.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, in-memory workspace metadata baselines for best-effort artifact discovery.
 *
 * <p>The audit deliberately stores metadata only. It is not a transaction log and does not
 * provide rollback. Symbolic links and well-known dependency, build, cache, state and credential
 * directories are excluded. Any scan limit or observed concurrent mutation makes the affected
 * result incomplete instead of presenting a partial scan as an empty change set.</p>
 */
public final class WorkspaceAudit {

    public static final String AUDIT_NOTE =
            "Best-effort filtered workspace metadata diff; not a complete transaction record.";

    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".hg", ".svn", "node_modules", "dist", "build", "out", "target",
            "__pycache__", ".venv", "venv", ".cache", ".snz1dp", "coverage", ".idea",
            ".vscode", ".next", ".nuxt", ".gradle", "state", "state-dir", ".state",
            ".taiyiflow", "credentials", ".credentials", "credential-store",
            ".credential-store", "secrets", ".secrets");

    private final Path root;
    private final Limits limits;
    private final Map<Bucket, Scan> baselines = new ConcurrentHashMap<>();
    private final Map<Bucket, Set<String>> reportedChanges = new ConcurrentHashMap<>();
    private final Map<Bucket, Object> bucketLocks = new ConcurrentHashMap<>();

    public WorkspaceAudit(Path root) {
        this(root, Limits.defaults());
    }

    public WorkspaceAudit(Path root, Limits limits) {
        this.root = (root == null ? Path.of("") : root).toAbsolutePath().normalize();
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Get or lazily create the metadata baseline for a session/run bucket. */
    public Scan captureBaseline(String sessionId, String runId) {
        Bucket bucket = Bucket.of(sessionId, runId);
        synchronized (lockFor(bucket)) {
            Scan existing = baselines.get(bucket);
            if (existing != null) {
                return existing;
            }
            Scan created = scan();
            // Degraded baselines are cached too, avoiding a full retry after every command.
            baselines.put(bucket, created);
            return created;
        }
    }

    /**
     * Diff the current workspace against a captured baseline and refresh that bucket's baseline.
     * Declared paths are excluded from scan-generated changes. Fingerprints also deduplicate
     * overlapping scans in the same session/run without hiding later, different revisions.
     */
    public AuditResult diffAndRefresh(String sessionId, String runId, Scan baseline,
                                      Set<Path> declaredPaths) {
        Objects.requireNonNull(baseline, "baseline");
        Bucket bucket = Bucket.of(sessionId, runId);
        synchronized (lockFor(bucket)) {
            if (!baseline.complete()) {
                return new AuditResult(false, baseline.degradationReasons(), List.of(),
                        baseline.scannedEntries());
            }

            Scan current = scan();
            baselines.put(bucket, current);
            if (!current.complete()) {
                return new AuditResult(false,
                        mergeReasons(baseline.degradationReasons(), current.degradationReasons()),
                        List.of(), current.scannedEntries());
            }

            Set<Path> declared = normalizePaths(declaredPaths);
            List<Change> candidates = new ArrayList<>();
            for (Map.Entry<Path, FileMetadata> item : current.entries().entrySet()) {
                Path path = item.getKey();
                if (declared.contains(path)) {
                    continue;
                }
                FileMetadata before = baseline.entries().get(path);
                FileMetadata after = item.getValue();
                if (before == null) {
                    candidates.add(Change.unhashed("create", path, after));
                } else if (!before.equals(after)) {
                    candidates.add(Change.unhashed("edit", path, after));
                }
            }
            for (Map.Entry<Path, FileMetadata> item : baseline.entries().entrySet()) {
                Path path = item.getKey();
                if (!declared.contains(path) && !current.entries().containsKey(path)) {
                    candidates.add(new Change("delete", path, null, null, null));
                }
            }
            candidates.sort(Comparator.comparing(change -> change.path().toString()));

            List<Change> hashed = new ArrayList<>(candidates.size());
            int hashes = 0;
            try {
                for (Change candidate : candidates) {
                    if (candidate.metadata() != null && hashes < limits.maxHashes()
                            && candidate.size() <= limits.maxHashBytes()) {
                        String sha256 = hashCandidate(candidate.path(), candidate.metadata());
                        hashed.add(candidate.withHash(sha256));
                        hashes++;
                    } else {
                        hashed.add(candidate);
                    }
                }
            } catch (ScanDegradation degraded) {
                Scan incomplete = Scan.degraded(degraded.reason, current.scannedEntries());
                baselines.put(bucket, incomplete);
                return new AuditResult(false, List.of(degraded.reason), List.of(),
                        current.scannedEntries());
            }

            Set<String> reported = reportedChanges.computeIfAbsent(bucket,
                    ignored -> ConcurrentHashMap.newKeySet());
            List<Change> changes = new ArrayList<>();
            for (Change candidate : hashed) {
                String fingerprint = fingerprint(candidate);
                // add is atomic for concurrent callers and false means this exact revision was
                // already emitted through this bucket's scan channel.
                if (reported.add(fingerprint)) {
                    changes.add(candidate);
                }
            }
            return new AuditResult(true, List.of(), List.copyOf(changes), current.scannedEntries());
        }
    }

    /** Clear one bucket, or all in-memory baselines when both identifiers are null. */
    public void clear(String sessionId, String runId) {
        if (sessionId == null && runId == null) {
            baselines.clear();
            reportedChanges.clear();
            bucketLocks.clear();
            return;
        }
        Bucket bucket = Bucket.of(sessionId, runId);
        baselines.remove(bucket);
        reportedChanges.remove(bucket);
        bucketLocks.remove(bucket);
    }

    public Path root() {
        return root;
    }

    public static boolean isIgnoredDirectory(String name) {
        return name != null && IGNORED_DIRECTORIES.contains(name);
    }

    private Object lockFor(Bucket bucket) {
        return bucketLocks.computeIfAbsent(bucket, ignored -> new Object());
    }

    private Set<Path> normalizePaths(Set<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return Set.of();
        }
        Set<Path> normalized = new HashSet<>();
        for (Path path : paths) {
            if (path != null) {
                normalized.add(normalizeDeclaredPath(path));
            }
        }
        return normalized;
    }

    private Path normalizeDeclaredPath(Path path) {
        Path absolute = (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
        try {
            Path ancestor = absolute;
            while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                ancestor = ancestor.getParent();
            }
            if (ancestor == null) {
                return absolute;
            }
            return ancestor.toRealPath().resolve(ancestor.relativize(absolute)).normalize();
        } catch (IOException | SecurityException ignored) {
            return absolute;
        }
    }

    private Scan scan() {
        long started = System.nanoTime();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(limits.maxScanMillis());
        Map<Path, FileMetadata> entries = new LinkedHashMap<>();
        Map<Path, Long> directoryMtimes = new HashMap<>();
        try {
            checkTimeBudget(started, budgetNanos);
            Path scanRoot = root.toRealPath();
            if (!Files.isDirectory(scanRoot)) {
                return Scan.degraded("SCAN_IO_ERROR", 0);
            }
            Files.walkFileTree(scanRoot, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new FileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            checkTimeBudget(started, budgetNanos);
                            int depth = scanRoot.equals(dir)
                                    ? 0 : scanRoot.relativize(dir).getNameCount();
                            if (depth > 0 && isIgnoredDirectory(dir.getFileName().toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (depth > limits.maxDepth()) {
                                throw new ScanDegradation("MAX_DEPTH_EXCEEDED");
                            }
                            directoryMtimes.put(dir, attrs.lastModifiedTime().toMillis());
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            checkTimeBudget(started, budgetNanos);
                            if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (file.getFileName().toString().startsWith(".taiyiflow-")
                                    && file.getFileName().toString().endsWith(".tmp")) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (entries.size() >= limits.maxEntries()) {
                                throw new ScanDegradation("MAX_ENTRIES_EXCEEDED");
                            }
                            BasicFileAttributes confirmed = readAttributes(file);
                            if (attrs.size() != confirmed.size()
                                    || attrs.lastModifiedTime().toMillis()
                                    != confirmed.lastModifiedTime().toMillis()) {
                                throw new ScanDegradation("SCAN_CONFLICT");
                            }
                            entries.put(file.toAbsolutePath().normalize(), new FileMetadata(
                                    confirmed.size(), confirmed.lastModifiedTime().toMillis()));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            if (exc instanceof NoSuchFileException) {
                                throw new ScanDegradation("SCAN_CONFLICT");
                            }
                            throw new ScanDegradation("SCAN_IO_ERROR");
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                            if (exc != null) {
                                throw new ScanDegradation("SCAN_IO_ERROR");
                            }
                            checkTimeBudget(started, budgetNanos);
                            Long before = directoryMtimes.get(dir);
                            if (before != null) {
                                long after = readAttributes(dir).lastModifiedTime().toMillis();
                                if (before != after) {
                                    throw new ScanDegradation("SCAN_CONFLICT");
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        private BasicFileAttributes readAttributes(Path path) {
                            try {
                                return Files.readAttributes(path, BasicFileAttributes.class,
                                        LinkOption.NOFOLLOW_LINKS);
                            } catch (NoSuchFileException e) {
                                throw new ScanDegradation("SCAN_CONFLICT");
                            } catch (IOException e) {
                                throw new ScanDegradation("SCAN_IO_ERROR");
                            }
                        }
                    });
            return new Scan(true, List.of(), entries, entries.size());
        } catch (ScanDegradation degraded) {
            return Scan.degraded(degraded.reason, entries.size());
        } catch (NoSuchFileException e) {
            return Scan.degraded("SCAN_CONFLICT", entries.size());
        } catch (IOException | SecurityException e) {
            return Scan.degraded("SCAN_IO_ERROR", entries.size());
        }
    }

    private String hashCandidate(Path path, FileMetadata expected) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            FileMetadata actual = new FileMetadata(after.size(), after.lastModifiedTime().toMillis());
            if (!expected.equals(actual)) {
                throw new ScanDegradation("SCAN_CONFLICT");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchFileException e) {
            throw new ScanDegradation("SCAN_CONFLICT");
        } catch (IOException | SecurityException e) {
            throw new ScanDegradation("SCAN_IO_ERROR");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void checkTimeBudget(long started, long budgetNanos) {
        if (System.nanoTime() - started >= budgetNanos) {
            throw new ScanDegradation("TIME_BUDGET_EXCEEDED");
        }
    }

    private static String fingerprint(Change change) {
        return change.type() + '|' + change.path() + '|' + change.size() + '|'
                + change.mtime() + '|' + change.sha256();
    }

    private static List<String> mergeReasons(List<String> first, List<String> second) {
        Set<String> merged = new LinkedHashSet<>(first);
        merged.addAll(second);
        return List.copyOf(merged);
    }

    private record Bucket(String sessionId, String runId) {
        static Bucket of(String sessionId, String runId) {
            return new Bucket(sessionId == null ? "" : sessionId, runId == null ? "" : runId);
        }
    }

    private static final class ScanDegradation extends RuntimeException {
        private final String reason;

        private ScanDegradation(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }
    }

    /** Scan budgets. Hashing is an additional, separately bounded candidate-only operation. */
    public record Limits(int maxDepth, int maxEntries, long maxScanMillis,
                         int maxHashes, long maxHashBytes) {
        public Limits {
            if (maxDepth < 0 || maxEntries < 0 || maxScanMillis < 0
                    || maxHashes < 0 || maxHashBytes < 0) {
                throw new IllegalArgumentException("Workspace audit limits must be non-negative");
            }
        }

        public static Limits defaults() {
            return new Limits(12, 20_000, 250, 256, 16L * 1024L * 1024L);
        }
    }

    /** File metadata retained in a baseline. */
    public record FileMetadata(long size, long mtime) {
    }

    /** A complete or explicitly degraded scan. Partial entries are never exposed as complete. */
    public record Scan(boolean complete, List<String> degradationReasons,
                       Map<Path, FileMetadata> entries, int scannedEntries) {
        public Scan {
            degradationReasons = List.copyOf(degradationReasons);
            entries = complete ? CollectionsSupport.unmodifiableCopy(entries) : Map.of();
            if (complete && !degradationReasons.isEmpty()) {
                throw new IllegalArgumentException("A complete scan cannot have degradation reasons");
            }
            if (!complete && degradationReasons.isEmpty()) {
                throw new IllegalArgumentException("A degraded scan requires a reason");
            }
        }

        static Scan degraded(String reason, int scannedEntries) {
            return new Scan(false, List.of(reason), Map.of(), scannedEntries);
        }
    }

    /** Artifact-oriented change metadata. Null size/mtime/hash denote unavailable values. */
    public record Change(String type, Path path, Long size, Long mtime, String sha256) {
        FileMetadata metadata() {
            return size == null || mtime == null ? null : new FileMetadata(size, mtime);
        }

        Change withHash(String hash) {
            return new Change(type, path, size, mtime, hash);
        }

        static Change unhashed(String type, Path path, FileMetadata metadata) {
            return new Change(type, path, metadata.size(), metadata.mtime(), null);
        }
    }

    /** Diff output; complete=false always accompanies one or more degradation reasons. */
    public record AuditResult(boolean complete, List<String> degradationReasons,
                              List<Change> changes, int scannedEntries) {
        public AuditResult {
            degradationReasons = List.copyOf(degradationReasons);
            changes = List.copyOf(changes);
            if (complete && !degradationReasons.isEmpty()) {
                throw new IllegalArgumentException("A complete audit cannot have degradation reasons");
            }
            if (!complete && degradationReasons.isEmpty()) {
                throw new IllegalArgumentException("A degraded audit requires a reason");
            }
        }
    }

    /** Keeps the public record constructor compact without exposing a mutable baseline map. */
    private static final class CollectionsSupport {
        private static Map<Path, FileMetadata> unmodifiableCopy(Map<Path, FileMetadata> source) {
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }
}
