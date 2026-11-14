package io.taiyiflow.sdk.plugins.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SearchPluginTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @TempDir
    Path outside;

    private ToolCall makeCall(String jsonArgs) throws Exception {
        return new ToolCall("tc-1", "search", mapper.readTree(jsonArgs));
    }

    @Test
    void textSearchFindsMatches() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "line one\nhello world\nline three");
        Files.writeString(tempDir.resolve("other.txt"), "nothing here");

        SearchPlugin plugin = new SearchPlugin(tempDir);
        ToolResult result = plugin.search(makeCall("{\"pattern\":\"hello\"}"));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getResult().get("totalMatches").asInt());
        assertEquals("hello.txt", result.getResult().get("matches").get(0).get("file").asText());
        assertEquals(2, result.getResult().get("matches").get(0).get("line").asInt());
    }

    @Test
    void regexSearchFindsMatches() throws Exception {
        Files.writeString(tempDir.resolve("code.java"), "public void foo() {}\nprivate int bar;");

        SearchPlugin plugin = new SearchPlugin(tempDir);
        ToolResult result = plugin.search(makeCall("{\"pattern\":\"public\\\\s+void\",\"regex\":true}"));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getResult().get("totalMatches").asInt());
    }

    @Test
    void noMatchesReturnsEmpty() throws Exception {
        Files.writeString(tempDir.resolve("file.txt"), "abc def");

        SearchPlugin plugin = new SearchPlugin(tempDir);
        ToolResult result = plugin.search(makeCall("{\"pattern\":\"xyz\"}"));

        assertTrue(result.isSuccess());
        assertEquals(0, result.getResult().get("totalMatches").asInt());
    }

    @Test
    void missingPatternReturnsError() throws Exception {
        SearchPlugin plugin = new SearchPlugin(tempDir);
        ToolResult result = plugin.search(makeCall("{}"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Missing 'pattern'"));
    }

    @Test
    void sandboxEscapeBlocked() throws Exception {
        SearchPlugin plugin = new SearchPlugin(tempDir);
        ToolResult result = plugin.search(makeCall("{\"pattern\":\"root\",\"path\":\"../../../etc\"}"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("sandbox"));
    }

    @Test
    void symbolicLinkEscapeBlocked() throws Exception {
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(tempDir.resolve("outside"), outside);
        SearchPlugin plugin = new SearchPlugin(tempDir);

        ToolResult directoryResult = plugin.search(makeCall("{\"pattern\":\"secret\",\"path\":\"outside\"}"));
        ToolResult rootResult = plugin.search(makeCall("{\"pattern\":\"secret\"}"));

        assertFalse(directoryResult.isSuccess());
        assertTrue(rootResult.isSuccess());
        assertEquals(0, rootResult.getResult().get("totalMatches").asInt());
    }

    @Test
    void searchInSubdirectory() throws Exception {
        Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("sub/nested.txt"), "find me");

        SearchPlugin plugin = new SearchPlugin(tempDir);
        ToolResult result = plugin.search(makeCall("{\"pattern\":\"find\",\"path\":\"sub\"}"));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getResult().get("totalMatches").asInt());
    }

    @Test
    void globReturnsOnlyRelativeSandboxFilesAndDoesNotFollowLinks() throws Exception {
        Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("root.txt"), "root");
        Files.writeString(tempDir.resolve("sub/nested.txt"), "nested");
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(tempDir.resolve("linked.txt"), outside.resolve("secret.txt"));
        SearchPlugin plugin = new SearchPlugin(tempDir);

        ToolResult result = plugin.glob(new ToolCall("1", "glob_files",
                mapper.createObjectNode().put("pattern", "**/*.txt")));

        assertTrue(result.isSuccess(), result::getError);
        assertTrue(result.getResult().get("files").toString().contains("nested.txt"));
        assertFalse(result.getResult().get("files").toString().contains("secret.txt"));
        assertFalse(result.getResult().get("files").toString().contains("linked.txt"));
        assertFalse(plugin.glob(new ToolCall("2", "glob_files",
                mapper.createObjectNode().put("path", "../"))).isSuccess());
    }

    @Test
    void registerAddsAliases() {
        PluginManager pm = new PluginManager();
        SearchPlugin.register(pm, tempDir);
        assertTrue(pm.hasHandler("search"));
        assertTrue(pm.hasHandler("grep"));
        assertTrue(pm.hasHandler("glob_files"));
    }
}
