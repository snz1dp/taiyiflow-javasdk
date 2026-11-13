package io.taiyiflow.sdk.plugins.files;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FilePluginTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @TempDir
    Path outside;

    private ToolCall makeCall(String toolName, String jsonArgs) throws Exception {
        return new ToolCall("tc-1", toolName, mapper.readTree(jsonArgs));
    }

    private ToolCall editCall(String oldString, String newString, String expectedSha,
                              boolean replaceAll, boolean dryRun) {
        ObjectNode args = mapper.createObjectNode();
        args.put("path", "file.txt");
        args.put("old_string", oldString);
        args.put("new_string", newString);
        args.put("replace_all", replaceAll);
        args.put("dry_run", dryRun);
        if (expectedSha != null) {
            args.put("expected_sha256_before", expectedSha);
        }
        return new ToolCall("tc-1", "file_edit", args);
    }

    private String currentSha(FilePlugin plugin) throws Exception {
        ToolResult read = plugin.readFile(makeCall("read_file", "{\"path\":\"file.txt\"}"));
        assertTrue(read.isSuccess());
        return read.getResult().get("sha256").asText();
    }

    private void assertNoTemporaryFiles() throws Exception {
        try (var files = Files.list(tempDir)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".taiyiflow-")));
        }
    }

    @Test
    void writeAndReadFile() throws Exception {
        FilePlugin plugin = new FilePlugin(tempDir);

        ToolResult writeResult = plugin.writeFile(makeCall("write_file",
                "{\"path\":\"test.txt\",\"content\":\"hello world\"}"));
        assertTrue(writeResult.isSuccess());
        assertTrue(Files.exists(tempDir.resolve("test.txt")));

        ToolResult readResult = plugin.readFile(makeCall("read_file",
                "{\"path\":\"test.txt\"}"));
        assertTrue(readResult.isSuccess());
        assertEquals("hello world", readResult.getResult().get("content").asText());
    }

    @Test
    void writeCreatesParentDirectories() throws Exception {
        FilePlugin plugin = new FilePlugin(tempDir);
        ToolResult result = plugin.writeFile(makeCall("write_file",
                "{\"path\":\"sub/dir/file.txt\",\"content\":\"nested\"}"));
        assertTrue(result.isSuccess());
        assertTrue(Files.exists(tempDir.resolve("sub/dir/file.txt")));
    }

    @Test
    void writeLocksAreReleasedAndRemovedForEveryPath() throws Exception {
        FilePlugin plugin = new FilePlugin(tempDir);
        for (int index = 0; index < 20; index++) {
            ToolResult result = plugin.writeFile(makeCall("write_file",
                    "{\"path\":\"file-" + index + ".txt\",\"content\":\"value\"}"));
            assertTrue(result.isSuccess());
        }

        assertEquals(0, FilePlugin.writeLockCount());
    }

    @Test
    void readNonExistentFileReturnsError() throws Exception {
        FilePlugin plugin = new FilePlugin(tempDir);
        ToolResult result = plugin.readFile(makeCall("read_file", "{\"path\":\"nope.txt\"}"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("File not found"));
    }

    @Test
    void sandboxEscapeBlocked() throws Exception {
        FilePlugin plugin = new FilePlugin(tempDir);
        ToolResult result = plugin.readFile(makeCall("read_file", "{\"path\":\"../../../etc/passwd\"}"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("sandbox") || result.getError().contains("escapes"));
    }

    @Test
    void writeSandboxEscapeBlocked() throws Exception {
        FilePlugin plugin = new FilePlugin(tempDir);
        ToolResult result = plugin.writeFile(makeCall("write_file",
                "{\"path\":\"../../evil.txt\",\"content\":\"bad\"}"));
        assertFalse(result.isSuccess());
    }

    @Test
    void symbolicLinkReadEscapeBlocked() throws Exception {
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(tempDir.resolve("outside"), outside);
        FilePlugin plugin = new FilePlugin(tempDir);

        ToolResult result = plugin.readFile(makeCall("read_file", "{\"path\":\"outside/secret.txt\"}"));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("escapes sandbox"));
    }

    @Test
    void symbolicLinkWriteIsRejected() throws Exception {
        Path outsideFile = outside.resolve("outside.txt");
        Files.writeString(outsideFile, "original");
        Files.createSymbolicLink(tempDir.resolve("link.txt"), outsideFile);
        FilePlugin plugin = new FilePlugin(tempDir);

        ToolResult result = plugin.writeFile(makeCall("write_file",
                "{\"path\":\"link.txt\",\"content\":\"overwritten\"}"));

        assertFalse(result.isSuccess());
        assertEquals("original", Files.readString(outsideFile));
    }

    @Test
    void listFilesReturnsEntries() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "aaa");
        Files.createDirectory(tempDir.resolve("subdir"));

        FilePlugin plugin = new FilePlugin(tempDir);
        ToolResult result = plugin.listFiles(makeCall("list_files", "{\"path\":\".\"}"));
        assertTrue(result.isSuccess());
        assertTrue(result.getResult().get("files").size() >= 2);
    }

    @Test
    void listNonDirectoryReturnsError() throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), "content");
        FilePlugin plugin = new FilePlugin(tempDir);
        ToolResult result = plugin.listFiles(makeCall("list_files", "{\"path\":\"file.txt\"}"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Not a directory"));
    }

    @Test
    void missingPathArgumentReturnsError() throws Exception {
        FilePlugin plugin = new FilePlugin(tempDir);
        ToolResult result = plugin.readFile(makeCall("read_file", "{}"));
        assertFalse(result.isSuccess());
    }

    @Test
    void atomicMoveUnsupportedDoesNotTruncateTargetAndCleansTempFile() throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), "original");
        FilePlugin plugin = new FilePlugin(tempDir, (source, target) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
        });

        ToolResult result = plugin.writeFile(makeCall("write_file",
                "{\"path\":\"file.txt\",\"content\":\"replacement\"}"));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Atomic same-directory move is not supported"));
        assertEquals("original", Files.readString(tempDir.resolve("file.txt")));
        assertNoTemporaryFiles();
    }

    @Test
    void replacementPreservesPosixPermissions() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView(PosixFileAttributeView.class));
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "original");
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rwxr-----");
        Files.setPosixFilePermissions(file, permissions);
        FilePlugin plugin = new FilePlugin(tempDir);

        ToolResult result = plugin.writeFile(makeCall("write_file",
                "{\"path\":\"file.txt\",\"content\":\"replacement\"}"));

        assertTrue(result.isSuccess(), result::getError);
        assertEquals(permissions, Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void newAtomicFileUsesPrivatePosixPermissions() throws Exception {
        assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView(PosixFileAttributeView.class));
        FilePlugin plugin = new FilePlugin(tempDir);

        ToolResult result = plugin.writeFile(makeCall("write_file",
                "{\"path\":\"file.txt\",\"content\":\"secret\"}"));

        assertTrue(result.isSuccess(), result::getError);
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(tempDir.resolve("file.txt"), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void editReplacesOneUtf8MatchAndReturnsHashes() throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), "hello 世界\n");
        FilePlugin plugin = new FilePlugin(tempDir);
        String beforeSha = currentSha(plugin);

        ToolResult result = plugin.editFile(editCall("世界", "太乙", beforeSha, false, false));

        assertTrue(result.isSuccess(), result::getError);
        assertEquals(1, result.getResult().get("replacements").asInt());
        assertEquals(1, result.getResult().get("occurrences").asInt());
        assertTrue(result.getResult().get("changed").asBoolean());
        assertEquals(beforeSha, result.getResult().get("sha256_before").asText());
        assertNotEquals(beforeSha, result.getResult().get("sha256_after").asText());
        assertEquals("hello 太乙\n", Files.readString(tempDir.resolve("file.txt")));
        assertNoTemporaryFiles();
    }

    @Test
    void editZeroAndMultipleMatchesReturnStructuredConflicts() throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), "foo foo");
        FilePlugin plugin = new FilePlugin(tempDir);

        ToolResult missing = plugin.editFile(editCall("bar", "baz", null, false, false));
        assertFalse(missing.isSuccess());
        assertEquals("PATCH_CONFLICT", missing.getResult().get("code").asText());
        assertEquals("MATCH_NOT_FOUND", missing.getResult().get("reason").asText());

        ToolResult ambiguous = plugin.editFile(editCall("foo", "baz", null, false, false));
        assertFalse(ambiguous.isSuccess());
        assertEquals("AMBIGUOUS_MATCH", ambiguous.getResult().get("reason").asText());
        assertEquals(2, ambiguous.getResult().get("occurrences").asInt());
        assertEquals("foo foo", Files.readString(tempDir.resolve("file.txt")));
    }

    @Test
    void editReplaceAllAndDryRunHaveCorrectSideEffects() throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), "foo foo");
        FilePlugin plugin = new FilePlugin(tempDir);

        ToolResult dryRun = plugin.editFile(editCall("foo", "bar", null, true, true));
        assertTrue(dryRun.isSuccess(), dryRun::getError);
        assertEquals(2, dryRun.getResult().get("replacements").asInt());
        assertTrue(dryRun.getResult().get("dry_run").asBoolean());
        assertTrue(dryRun.getResult().get("changed").asBoolean());
        assertEquals("foo foo", Files.readString(tempDir.resolve("file.txt")));

        ToolResult applied = plugin.editFile(editCall("foo", "bar", null, true, false));
        assertTrue(applied.isSuccess(), applied::getError);
        assertEquals("bar bar", Files.readString(tempDir.resolve("file.txt")));
    }

    @Test
    void staleExpectedShaDoesNotOverwriteNewContent() throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), "current");
        FilePlugin plugin = new FilePlugin(tempDir);

        ToolResult result = plugin.editFile(editCall("current", "replacement",
                "0".repeat(64), false, false));

        assertFalse(result.isSuccess());
        assertEquals("STALE_CONTENT", result.getResult().get("reason").asText());
        assertEquals("current", Files.readString(tempDir.resolve("file.txt")));
        assertNoTemporaryFiles();
    }

    @Test
    void concurrentEditsWithSameExpectedShaOnlyApplyOnce() throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), "old");
        FilePlugin plugin = new FilePlugin(tempDir);
        String beforeSha = currentSha(plugin);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ToolResult>> futures = new ArrayList<>();
            for (String replacement : List.of("first", "second")) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return plugin.editFile(editCall("old", replacement, beforeSha, false, false));
                }));
            }
            ready.await();
            start.countDown();
            ToolResult first = futures.get(0).get();
            ToolResult second = futures.get(1).get();
            assertNotEquals(first.isSuccess(), second.isSuccess());
            ToolResult conflict = first.isSuccess() ? second : first;
            assertEquals("STALE_CONTENT", conflict.getResult().get("reason").asText());
            assertTrue(List.of("first", "second").contains(
                    Files.readString(tempDir.resolve("file.txt"))));
            assertNoTemporaryFiles();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void editAdaptsOnlyLineEndingsNotWhitespace() throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), "a\r\nb\r\n");
        FilePlugin plugin = new FilePlugin(tempDir);

        ToolResult newline = plugin.editFile(editCall("a\nb", "x\ny", null, false, false));
        assertTrue(newline.isSuccess(), newline::getError);
        assertTrue(newline.getResult().get("normalized_old_string_match").asBoolean());
        assertEquals("x\r\ny\r\n", Files.readString(tempDir.resolve("file.txt")));

        Files.writeString(tempDir.resolve("file.txt"), "  indented");
        ToolResult whitespace = plugin.editFile(editCall("indented ", "changed", null, false, false));
        assertFalse(whitespace.isSuccess());
        assertEquals("MATCH_NOT_FOUND", whitespace.getResult().get("reason").asText());
        assertEquals("  indented", Files.readString(tempDir.resolve("file.txt")));
    }

    @Test
    void editRejectsSymbolicLink() throws Exception {
        Path outsideFile = outside.resolve("outside.txt");
        Files.writeString(outsideFile, "original");
        Files.createSymbolicLink(tempDir.resolve("file.txt"), outsideFile);
        FilePlugin plugin = new FilePlugin(tempDir);

        ToolResult result = plugin.editFile(editCall("original", "changed", null, false, false));

        assertFalse(result.isSuccess());
        assertEquals("original", Files.readString(outsideFile));
        assertNoTemporaryFiles();
    }

    @Test
    void registerAddsAllHandlers() {
        PluginManager pm = new PluginManager();
        FilePlugin.register(pm, tempDir);
        assertTrue(pm.hasHandler("read_file"));
        assertTrue(pm.hasHandler("write_file"));
        assertTrue(pm.hasHandler("file_edit"));
        assertTrue(pm.hasHandler("list_files"));
    }
}
