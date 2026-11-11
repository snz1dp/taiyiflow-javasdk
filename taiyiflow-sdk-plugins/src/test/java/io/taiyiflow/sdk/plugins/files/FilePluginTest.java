package io.taiyiflow.sdk.plugins.files;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FilePluginTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @TempDir
    Path outside;

    private ToolCall makeCall(String toolName, String jsonArgs) throws Exception {
        return new ToolCall("tc-1", toolName, mapper.readTree(jsonArgs));
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
    void registerAddsAllHandlers() {
        PluginManager pm = new PluginManager();
        FilePlugin.register(pm, tempDir);
        assertTrue(pm.hasHandler("read_file"));
        assertTrue(pm.hasHandler("write_file"));
        assertTrue(pm.hasHandler("list_files"));
    }
}
