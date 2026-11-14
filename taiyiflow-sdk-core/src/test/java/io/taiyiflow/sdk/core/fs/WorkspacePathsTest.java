package io.taiyiflow.sdk.core.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspacePathsTest {
    @TempDir
    Path tempDir;

    @Test
    void sessionsAreIsolatedAndEscapesAreRejected() throws Exception {
        Path first = WorkspacePaths.sessionWorkspace(tempDir, "flow", "one");
        Path second = WorkspacePaths.sessionWorkspace(tempDir, "flow", "two");
        assertNotEquals(first, second);
        Files.writeString(first.resolve("file.txt"), "ok");
        assertEquals("ok", Files.readString(WorkspacePaths.resolveExisting(first, "file.txt")));
        assertThrows(SecurityException.class, () -> WorkspacePaths.resolveExisting(first, "../outside"));
    }

    @Test
    void symbolicLinkEscapeIsRejectedBeforeCreatingDirectories() throws Exception {
        Path workspace = WorkspacePaths.sessionWorkspace(tempDir, "flow", "one");
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.createSymbolicLink(workspace.resolve("link"), outside);
        assertThrows(SecurityException.class,
                () -> WorkspacePaths.resolveForWrite(workspace, "link/nested/file.txt"));
        assertFalse(Files.exists(outside.resolve("nested")));
    }
}
