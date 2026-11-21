package io.taiyiflow.sdk.plugins.files;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;

import static io.taiyiflow.sdk.plugins.ToolDefinitions.tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/** File read/write/edit plugin with sandbox restriction and atomic replacement. */
public class FilePlugin {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final ConcurrentMap<Path, LockReference> WRITE_LOCKS = new ConcurrentHashMap<>();

    private final Path sandboxRoot;
    private final AtomicMoveOperation atomicMove;
    private final io.taiyiflow.sdk.plugins.workspace.WorkspaceAudit workspaceAudit;

    public FilePlugin(Path sandboxRoot) {
        this(sandboxRoot, (source, target) -> Files.move(source, target,
                LinkOption.NOFOLLOW_LINKS, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING));
    }

    FilePlugin(Path sandboxRoot, AtomicMoveOperation atomicMove) {
        this(sandboxRoot, atomicMove, null);
    }

    FilePlugin(Path sandboxRoot, AtomicMoveOperation atomicMove,
               io.taiyiflow.sdk.plugins.workspace.WorkspaceAudit workspaceAudit) {
        this.sandboxRoot = sandboxRoot.toAbsolutePath().normalize();
        this.atomicMove = atomicMove;
        this.workspaceAudit = workspaceAudit;
    }

    /** Mark successful writes so scan-discovered edits to these paths attribute to this session. */
    private void markSnapshot(io.taiyiflow.sdk.core.dispatch.ToolExecutionContext context, ToolResult result) {
        if (workspaceAudit == null || context == null || result == null || !result.isSuccess()) {
            return;
        }
        JsonNode content = result.getResult();
        if (content == null || !content.hasNonNull("path")) {
            return;
        }
        boolean written = content.path("written").asBoolean(false)
                || ("applied".equals(content.path("status").asText())
                        && !content.path("dry_run").asBoolean(false));
        if (written) {
            workspaceAudit.markSnapshot(context.getSessionId(), Path.of(content.get("path").asText()));
        }
    }

    public ToolResult readFile(ToolCall toolCall) {
        JsonNode args = toolCall.getArguments();
        if (args == null || !args.has("path")) {
            return ToolResult.error(toolCall.getId(), "Missing 'path' argument");
        }
        try {
            Path requested = resolvePath(args.get("path").asText());
            if (!Files.exists(requested)) {
                return ToolResult.error(toolCall.getId(), "File not found: " + requested);
            }
            Path filePath = resolveExistingPath(args.get("path").asText());
            byte[] bytes = readBytes(filePath);
            ObjectNode result = mapper.createObjectNode();
            result.put("path", filePath.toString());
            result.put("content", decodeUtf8(bytes));
            result.put("size", bytes.length);
            result.put("sha256", sha256(bytes));
            return ToolResult.success(toolCall.getId(), result);
        } catch (Exception e) {
            return ToolResult.error(toolCall.getId(), "Read failed: " + safeMessage(e));
        }
    }

    public ToolResult writeFile(ToolCall toolCall) {
        JsonNode args = toolCall.getArguments();
        if (args == null || !args.has("path") || !args.has("content")) {
            return ToolResult.error(toolCall.getId(), "Missing 'path' or 'content' argument");
        }
        LockReference lock = null;
        try {
            Path filePath = resolveWritablePath(args.get("path").asText(), true);
            lock = lock(filePath);
            validateFinalTarget(filePath);
            byte[] content = args.get("content").asText().getBytes(StandardCharsets.UTF_8);
            atomicWrite(filePath, content, null);
            ObjectNode result = mapper.createObjectNode();
            result.put("path", filePath.toString());
            result.put("written", true);
            result.put("size", content.length);
            result.put("sha256_after", sha256(content));
            return ToolResult.success(toolCall.getId(), result);
        } catch (Exception e) {
            return ToolResult.error(toolCall.getId(), "Write failed: " + safeMessage(e));
        } finally {
            unlock(lock);
        }
    }

    /** Exact string edit with optimistic SHA-256 concurrency protection. */
    public ToolResult editFile(ToolCall toolCall) {
        JsonNode args = toolCall.getArguments();
        if (args == null || !args.has("path") || !args.has("old_string") || !args.has("new_string")) {
            return ToolResult.error(toolCall.getId(),
                    "Missing 'path', 'old_string' or 'new_string' argument");
        }
        String oldString = args.get("old_string").asText();
        if (oldString.isEmpty()) {
            return ToolResult.error(toolCall.getId(), "old_string must not be empty");
        }
        LockReference lock = null;
        try {
            Path filePath = resolveWritablePath(args.get("path").asText(), false);
            lock = lock(filePath);
            return editFileLocked(toolCall, filePath, args, oldString,
                    args.get("new_string").asText());
        } catch (Exception e) {
            return ToolResult.error(toolCall.getId(), "Edit failed: " + safeMessage(e));
        } finally {
            unlock(lock);
        }
    }

    private ToolResult editFileLocked(ToolCall toolCall, Path filePath, JsonNode args,
                                      String oldString, String newString) throws IOException {
        validateFinalTarget(filePath);
        byte[] beforeBytes = readBytes(filePath);
        String before = decodeUtf8(beforeBytes);
        String beforeHash = sha256(beforeBytes);
        String expectedHash = args.hasNonNull("expected_sha256_before")
                ? args.get("expected_sha256_before").asText() : null;
        boolean dryRun = args.path("dry_run").asBoolean(false);
        boolean replaceAll = args.path("replace_all").asBoolean(false);

        if (expectedHash != null && !expectedHash.isEmpty()
                && !expectedHash.equalsIgnoreCase(beforeHash)) {
            return patchConflict(toolCall, filePath, "STALE_CONTENT", beforeHash, expectedHash,
                    0, dryRun, oldString, "Expected content SHA-256 does not match current content");
        }

        LineEndingMatch match = adaptLineEndings(before, oldString, newString);
        int occurrences = countOccurrences(before, match.oldString());
        if (occurrences == 0) {
            return patchConflict(toolCall, filePath, "MATCH_NOT_FOUND", beforeHash, expectedHash,
                    0, dryRun, oldString, "old_string was not found in current content");
        }
        if (occurrences > 1 && !replaceAll) {
            return patchConflict(toolCall, filePath, "AMBIGUOUS_MATCH", beforeHash, expectedHash,
                    occurrences, dryRun, oldString,
                    "old_string is ambiguous; add context or set replace_all=true");
        }

        String after = replace(before, match.oldString(), match.newString(), replaceAll);
        byte[] afterBytes = after.getBytes(StandardCharsets.UTF_8);
        boolean changed = !before.equals(after);
        if (!dryRun && changed) {
            try {
                atomicWrite(filePath, afterBytes, beforeHash);
            } catch (StaleContentException stale) {
                return patchConflict(toolCall, filePath, "STALE_CONTENT", stale.currentSha256,
                        expectedHash == null ? beforeHash : expectedHash, occurrences, dryRun,
                        oldString, "File content changed before the atomic edit could be committed");
            }
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "applied");
        result.put("path", filePath.toString());
        result.put("dry_run", dryRun);
        result.put("replacements", replaceAll ? occurrences : 1);
        result.put("occurrences", occurrences);
        result.put("replace_all", replaceAll);
        result.put("bytes_before", beforeBytes.length);
        result.put("bytes_after", afterBytes.length);
        result.put("sha256_before", beforeHash);
        result.put("sha256_after", sha256(afterBytes));
        result.put("changed", changed);
        if (match.normalized()) {
            result.put("normalized_old_string_match", true);
        }
        return ToolResult.success(toolCall.getId(), result);
    }

    public ToolResult listFiles(ToolCall toolCall) {
        JsonNode args = toolCall.getArguments();
        String pathStr = (args != null && args.has("path")) ? args.get("path").asText() : ".";
        try {
            Path dirPath = resolveExistingPath(pathStr);
            if (!Files.isDirectory(dirPath)) {
                return ToolResult.error(toolCall.getId(), "Not a directory: " + dirPath);
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("path", dirPath.toString());
            var files = result.putArray("files");
            try (var stream = Files.list(dirPath)) {
                stream.forEach(p -> {
                    ObjectNode entry = files.addObject();
                    entry.put("name", p.getFileName().toString());
                    entry.put("directory", Files.isDirectory(p));
                    try {
                        entry.put("size", Files.isRegularFile(p) ? Files.size(p) : 0);
                    } catch (IOException e) {
                        entry.put("size", 0);
                    }
                });
            }
            return ToolResult.success(toolCall.getId(), result);
        } catch (Exception e) {
            return ToolResult.error(toolCall.getId(), "List failed: " + safeMessage(e));
        }
    }

    private void atomicWrite(Path target, byte[] content, String expectedBeforeSha256)
            throws IOException {
        validateFinalTarget(target);
        Path parent = target.getParent();
        Path temporary = null;
        boolean moved = false;
        try {
            temporary = createPrivateTempFile(parent);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (expectedBeforeSha256 != null) {
                String currentHash = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        ? sha256(readBytes(target)) : null;
                if (!expectedBeforeSha256.equals(currentHash)) {
                    throw new StaleContentException(currentHash);
                }
            }
            preserveExistingPosixPermissions(target, temporary);
            validateFinalTarget(target);
            if (!temporary.getParent().equals(target.getParent()) || !temporary.startsWith(parent)) {
                throw new SecurityException("Atomic temporary file escaped the target directory");
            }
            try {
                atomicMove.move(temporary, target);
                moved = true;
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Atomic same-directory move is not supported for " + target
                        + "; target was not modified", e);
            }
        } finally {
            if (!moved && temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    // Preserve the primary failure; unique private names prevent collisions.
                }
            }
        }
    }

    private static Path createPrivateTempFile(Path parent) throws IOException {
        try {
            FileAttribute<Set<PosixFilePermission>> attribute =
                    PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS);
            return Files.createTempFile(parent, ".taiyiflow-", ".tmp", attribute);
        } catch (UnsupportedOperationException e) {
            return Files.createTempFile(parent, ".taiyiflow-", ".tmp");
        }
    }

    private static void preserveExistingPosixPermissions(Path target, Path temporary)
            throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target,
                    LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(temporary, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX file systems retain their platform defaults.
        }
    }

    private Path resolveExistingPath(String path) throws IOException {
        Path resolved = resolvePath(path).toRealPath();
        requireRealInsideSandbox(resolved, path);
        return resolved;
    }

    private Path resolveWritablePath(String path, boolean createParents) throws IOException {
        Path resolved = resolvePath(path);
        if (resolved.getParent() == null || resolved.getFileName() == null) {
            throw new SecurityException("Path does not identify a file: " + path);
        }
        Path ancestor = resolved.getParent();
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new SecurityException("Path has no existing sandbox ancestor: " + path);
        }
        requireRealInsideSandbox(ancestor.toRealPath(), path);
        if (createParents) {
            Files.createDirectories(resolved.getParent());
        }
        Path realParent = resolved.getParent().toRealPath();
        requireRealInsideSandbox(realParent, path);
        Path target = realParent.resolve(resolved.getFileName().toString());
        if (Files.isSymbolicLink(target)) {
            throw new SecurityException("Symbolic link writes are not allowed: " + path);
        }
        return target;
    }

    private void validateFinalTarget(Path target) throws IOException {
        Path realParent = target.getParent().toRealPath();
        requireRealInsideSandbox(realParent, target.toString());
        if (!realParent.equals(target.getParent())) {
            throw new SecurityException("Target parent changed during atomic write: " + target);
        }
        if (Files.isSymbolicLink(target)) {
            throw new SecurityException("Symbolic link writes are not allowed: " + target);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("Atomic write target is not a regular file: " + target);
            }
        }
    }

    private Path resolvePath(String path) {
        Path resolved = sandboxRoot.resolve(path).normalize();
        if (!resolved.startsWith(sandboxRoot)) {
            throw new SecurityException("Path escapes sandbox: " + path);
        }
        return resolved;
    }

    private void requireRealInsideSandbox(Path resolved, String original) throws IOException {
        if (!resolved.startsWith(sandboxRoot.toRealPath())) {
            throw new SecurityException("Path escapes sandbox: " + original);
        }
    }

    private static byte[] readBytes(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                output.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
            return output.toByteArray();
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static LineEndingMatch adaptLineEndings(String content, String oldString,
                                                     String newString) {
        if (content.contains(oldString)) {
            return new LineEndingMatch(oldString, newString, false);
        }
        String normalizedOld = oldString.replace("\r\n", "\n");
        String normalizedNew = newString.replace("\r\n", "\n");
        if (!normalizedOld.equals(oldString) && content.contains(normalizedOld)) {
            return new LineEndingMatch(normalizedOld, normalizedNew, true);
        }
        String crlfOld = normalizedOld.replace("\n", "\r\n");
        if (!crlfOld.equals(oldString) && content.contains(crlfOld)) {
            return new LineEndingMatch(crlfOld, normalizedNew.replace("\n", "\r\n"), true);
        }
        return new LineEndingMatch(oldString, newString, false);
    }

    private static int countOccurrences(String content, String needle) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String replace(String content, String oldString, String newString,
                                  boolean replaceAll) {
        StringBuilder result = new StringBuilder(content.length());
        int copiedThrough = 0;
        int index;
        while ((index = content.indexOf(oldString, copiedThrough)) >= 0) {
            result.append(content, copiedThrough, index).append(newString);
            copiedThrough = index + oldString.length();
            if (!replaceAll) {
                break;
            }
        }
        return result.append(content, copiedThrough, content.length()).toString();
    }

    private static ToolResult patchConflict(ToolCall toolCall, Path path, String reason,
                                            String actualBeforeHash, String expectedBeforeHash,
                                            int occurrences, boolean dryRun, String oldString,
                                            String detail) {
        ObjectNode diagnostic = mapper.createObjectNode();
        diagnostic.put("code", "PATCH_CONFLICT");
        diagnostic.put("reason", reason);
        diagnostic.put("status", reason.toLowerCase(java.util.Locale.ROOT));
        diagnostic.put("path", path.toString());
        if (actualBeforeHash == null) {
            diagnostic.putNull("sha256_before");
        } else {
            diagnostic.put("sha256_before", actualBeforeHash);
        }
        if (expectedBeforeHash != null) {
            diagnostic.put("expected_sha256_before", expectedBeforeHash);
        }
        diagnostic.put("changed", false);
        diagnostic.put("occurrences", occurrences);
        diagnostic.put("replacements", 0);
        diagnostic.put("dry_run", dryRun);
        diagnostic.put("exact_match_required", true);
        diagnostic.put("preview_before", preview(oldString));
        ToolResult result = ToolResult.error(toolCall.getId(),
                "PATCH_CONFLICT/" + reason + ": " + detail);
        result.setResult(diagnostic);
        return result;
    }

    private static String preview(String value) {
        String trimmed = value.trim();
        return trimmed.length() <= 160 ? trimmed : trimmed.substring(0, 157) + "...";
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static LockReference lock(Path path) {
        LockReference reference = WRITE_LOCKS.compute(path, (ignored, current) -> {
            LockReference selected = current == null ? new LockReference(path) : current;
            selected.users++;
            return selected;
        });
        reference.lock.lock();
        return reference;
    }

    private static void unlock(LockReference reference) {
        if (reference == null || !reference.lock.isHeldByCurrentThread()) {
            return;
        }
        reference.lock.unlock();
        WRITE_LOCKS.computeIfPresent(reference.path, (ignored, current) -> {
            if (current != reference) {
                return current;
            }
            reference.users--;
            return reference.users == 0 ? null : reference;
        });
    }

    static int writeLockCount() {
        return WRITE_LOCKS.size();
    }

    public static void register(PluginManager pm, Path sandboxRoot) {
        register(pm, sandboxRoot, null);
    }

    /** Register with a shared workspace audit (nullable) for scan edit attribution. */
    public static void register(PluginManager pm, Path sandboxRoot,
                                io.taiyiflow.sdk.plugins.workspace.WorkspaceAudit workspaceAudit) {
        FilePlugin plugin = new FilePlugin(sandboxRoot, (source, target) -> Files.move(source, target,
                LinkOption.NOFOLLOW_LINKS, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING), workspaceAudit);
        String pathSchema = "{\"path\":{\"type\":\"string\"}}";
        pm.register(tool("read_file", "Read a UTF-8 file inside the workspace", pathSchema, "path"), plugin::readFile);
        pm.register(tool("write_file", "Write a UTF-8 file inside the workspace",
                "{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}}", "path", "content"),
                (PluginManager.ContextualToolHandler) (context, call) -> {
                    ToolResult result = plugin.writeFile(call);
                    plugin.markSnapshot(context, result);
                    return result;
                });
        pm.register(tool("file_edit", "Edit a UTF-8 file with exact string replacement",
                "{\"path\":{\"type\":\"string\"},\"old_string\":{\"type\":\"string\"},"
                        + "\"new_string\":{\"type\":\"string\"},\"expected_sha256_before\":{\"type\":\"string\"},"
                        + "\"dry_run\":{\"type\":\"boolean\"},\"replace_all\":{\"type\":\"boolean\"}}",
                "path", "old_string", "new_string"),
                (PluginManager.ContextualToolHandler) (context, call) -> {
                    ToolResult result = plugin.editFile(call);
                    plugin.markSnapshot(context, result);
                    return result;
                });
        pm.register(tool("list_files", "List files inside the workspace", pathSchema), plugin::listFiles);
    }

    public static void registerReadOnly(PluginManager pm, Path sandboxRoot) {
        FilePlugin plugin = new FilePlugin(sandboxRoot);
        String pathSchema = "{\"path\":{\"type\":\"string\"}}";
        pm.register(tool("read_file", "Read a UTF-8 file inside the workspace", pathSchema, "path"), plugin::readFile);
        pm.register(tool("list_files", "List files inside the workspace", pathSchema), plugin::listFiles);
    }

    @FunctionalInterface
    interface AtomicMoveOperation {
        void move(Path source, Path target) throws IOException;
    }

    private record LineEndingMatch(String oldString, String newString, boolean normalized) {
    }

    private static final class LockReference {
        private final Path path;
        private final ReentrantLock lock = new ReentrantLock();
        private int users;

        private LockReference(Path path) {
            this.path = path;
        }
    }

    private static final class StaleContentException extends IOException {
        private final String currentSha256;

        private StaleContentException(String currentSha256) {
            super("File content changed before atomic replacement");
            this.currentSha256 = currentSha256;
        }
    }
}
