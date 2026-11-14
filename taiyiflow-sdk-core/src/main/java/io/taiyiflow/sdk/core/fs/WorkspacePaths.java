package io.taiyiflow.sdk.core.fs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class WorkspacePaths {
    private WorkspacePaths() {
    }

    public static Path sessionWorkspace(Path configuredRoot, String flowId, String sessionId) throws IOException {
        if (configuredRoot == null) {
            throw new IllegalStateException("workspace is not configured");
        }
        String identity = flowId + "\0" + sessionId;
        String directory = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        Path root = configuredRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path workspace = root.resolve(directory).normalize();
        requireContained(root, workspace);
        Files.createDirectories(workspace);
        return workspace;
    }

    public static Path resolveExisting(Path root, String value) throws IOException {
        Path candidate = candidate(root, value);
        Path realRoot = root.toRealPath();
        Path realCandidate = candidate.toRealPath();
        requireContained(realRoot, realCandidate);
        return realCandidate;
    }

    public static Path resolveForWrite(Path root, String value) throws IOException {
        Path candidate = candidate(root, value);
        Path parent = candidate.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("A workspace-relative file path is required");
        }
        Path realRoot = root.toRealPath();
        Path existingAncestor = parent;
        while (!Files.exists(existingAncestor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.getParent();
            if (existingAncestor == null) {
                throw new SecurityException("Path has no existing workspace ancestor");
            }
        }
        requireContained(realRoot, existingAncestor.toRealPath());
        Files.createDirectories(parent);
        Path realParent = parent.toRealPath();
        requireContained(realRoot, realParent);
        Path resolved = realParent.resolve(candidate.getFileName()).normalize();
        requireContained(realRoot, resolved);
        if (Files.exists(resolved) && Files.isSymbolicLink(resolved)) {
            requireContained(realRoot, resolved.toRealPath());
        }
        return resolved;
    }

    private static Path candidate(Path root, String value) {
        if (root == null || value == null || value.isBlank()) {
            throw new IllegalArgumentException("workspace and path are required");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path candidate = normalizedRoot.resolve(value).normalize();
        requireContained(normalizedRoot, candidate);
        return candidate;
    }

    private static void requireContained(Path root, Path candidate) {
        if (!candidate.startsWith(root) || candidate.equals(root)) {
            throw new SecurityException("Path escapes the configured workspace");
        }
    }
}
