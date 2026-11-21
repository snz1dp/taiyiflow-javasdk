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
        assertTrue(pm.hasHandler("search_files"));
    }

    private ToolResult searchFiles(String jsonArgs) throws Exception {
        SearchPlugin plugin = new SearchPlugin(tempDir);
        return plugin.searchFiles(new ToolCall("tc-sf", "search_files", mapper.readTree(jsonArgs)));
    }

    @Test
    void searchFilesContentModeReturnsElectronResultShape() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello one  \nnothing\nhello two\n");

        ToolResult result = searchFiles("{\"pattern\":\"hello\"}");

        assertTrue(result.isSuccess(), result::getError);
        var json = result.getResult();
        assertEquals("content", json.get("output_mode").asText());
        assertEquals("java", json.get("engine").asText());
        assertEquals(".", json.get("root").asText());
        assertEquals(2, json.get("count").asInt());
        assertEquals(2, json.get("total_matches").asInt());
        assertEquals(0, json.get("offset").asInt());
        assertFalse(json.get("has_more").asBoolean());
        var first = json.get("matches").get(0);
        assertEquals("a.txt", first.get("path").asText());
        assertEquals("a.txt", first.get("relative_path").asText());
        assertEquals(1, first.get("line").asInt());
        assertEquals("hello one", first.get("text").asText());
    }

    @Test
    void searchFilesPaginationSkipsOffsetAndReportsHasMore() throws Exception {
        Files.writeString(tempDir.resolve("lines.txt"), "m1\nm2\nm3\nm4\nm5\n");

        ToolResult result = searchFiles("{\"pattern\":\"m\",\"offset\":1,\"maxResults\":2}");

        assertTrue(result.isSuccess(), result::getError);
        var json = result.getResult();
        assertEquals(2, json.get("count").asInt());
        assertEquals(3, json.get("total_matches").asInt());
        assertEquals(1, json.get("offset").asInt());
        assertTrue(json.get("has_more").asBoolean());
        assertEquals(2, json.get("matches").get(0).get("line").asInt());
        assertEquals(3, json.get("matches").get(1).get("line").asInt());
    }

    @Test
    void searchFilesMaxResultsIsCappedAt200() throws Exception {
        Files.writeString(tempDir.resolve("many.txt"), "x\n".repeat(250));

        ToolResult result = searchFiles("{\"pattern\":\"x\",\"maxResults\":500}");

        assertTrue(result.isSuccess(), result::getError);
        assertEquals(200, result.getResult().get("count").asInt());
        assertTrue(result.getResult().get("has_more").asBoolean());
    }

    @Test
    void searchFilesIsCaseSensitiveByDefaultAndLiteralUnlessIsRegexp() throws Exception {
        Files.writeString(tempDir.resolve("case.txt"), "Hello\na|b\na\n");

        assertEquals(0, searchFiles("{\"pattern\":\"hello\"}").getResult().get("total_matches").asInt());
        assertEquals(1, searchFiles("{\"pattern\":\"hello\",\"caseSensitive\":false}")
                .getResult().get("total_matches").asInt());
        assertEquals(1, searchFiles("{\"pattern\":\"a|b\"}").getResult().get("total_matches").asInt());
        assertEquals(2, searchFiles("{\"pattern\":\"a|b\",\"isRegexp\":true}")
                .getResult().get("total_matches").asInt());
    }

    @Test
    void searchFilesGlobIncludeAndExcludePatternsFilterFiles() throws Exception {
        Files.writeString(tempDir.resolve("keep.java"), "needle");
        Files.writeString(tempDir.resolve("skip.txt"), "needle");
        Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("sub/deep.java"), "needle");
        Files.writeString(tempDir.resolve("sub/excluded.java"), "needle");

        var globbed = searchFiles("{\"pattern\":\"needle\",\"glob\":\"**/*.java\"}").getResult();
        assertEquals(3, globbed.get("total_matches").asInt());

        var included = searchFiles("{\"pattern\":\"needle\",\"includePatterns\":[\"sub/**\"]}").getResult();
        assertEquals(2, included.get("total_matches").asInt());

        var excluded = searchFiles(
                "{\"pattern\":\"needle\",\"excludePatterns\":[\"**/excluded.java\",\"*.txt\"]}").getResult();
        assertEquals(2, excluded.get("total_matches").asInt());
    }

    @Test
    void searchFilesFilesWithMatchesAndCountModes() throws Exception {
        Files.writeString(tempDir.resolve("b.txt"), "hit\nhit\n");
        Files.writeString(tempDir.resolve("a.txt"), "hit\n");

        var filesMode = searchFiles("{\"pattern\":\"hit\",\"outputMode\":\"files_with_matches\"}").getResult();
        assertEquals("files_with_matches", filesMode.get("output_mode").asText());
        assertEquals(2, filesMode.get("count").asInt());
        assertEquals(3, filesMode.get("total_matches").asInt());
        assertEquals("a.txt", filesMode.get("files").get(0).get("relative_path").asText());
        assertEquals(1, filesMode.get("files").get(0).get("count").asInt());
        assertEquals("b.txt", filesMode.get("files").get(1).get("relative_path").asText());
        assertEquals(2, filesMode.get("files").get(1).get("count").asInt());

        var countMode = searchFiles("{\"pattern\":\"hit\",\"outputMode\":\"count\"}").getResult();
        assertEquals("count", countMode.get("output_mode").asText());
        assertEquals(3, countMode.get("count").asInt());
        assertEquals(3, countMode.get("total_matches").asInt());
        assertTrue(countMode.get("matches").isEmpty());
    }

    @Test
    void searchFilesSkipsHiddenHeavyDirsAndBinaryFilesUnlessIncluded() throws Exception {
        Files.writeString(tempDir.resolve("visible.txt"), "needle");
        Files.writeString(tempDir.resolve(".hidden.txt"), "needle");
        Files.createDirectory(tempDir.resolve("node_modules"));
        Files.writeString(tempDir.resolve("node_modules/dep.txt"), "needle");
        Files.writeString(tempDir.resolve("image.png"), "needle");

        var defaultScan = searchFiles("{\"pattern\":\"needle\"}").getResult();
        assertEquals(1, defaultScan.get("total_matches").asInt());
        assertEquals("visible.txt", defaultScan.get("matches").get(0).get("relative_path").asText());

        var hiddenScan = searchFiles("{\"pattern\":\"needle\",\"includeHidden\":true}").getResult();
        assertEquals(2, hiddenScan.get("total_matches").asInt());
    }

    @Test
    void searchFilesSingleFilePathAndSubdirectoryRoot() throws Exception {
        Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("sub/target.txt"), "needle");
        Files.writeString(tempDir.resolve("other.txt"), "needle");

        var single = searchFiles("{\"pattern\":\"needle\",\"path\":\"sub/target.txt\"}").getResult();
        assertEquals(1, single.get("total_matches").asInt());
        assertEquals("target.txt", single.get("matches").get(0).get("relative_path").asText());
        assertEquals("sub/target.txt", single.get("matches").get(0).get("path").asText());
        assertEquals("sub/target.txt", single.get("root").asText());

        var scoped = searchFiles("{\"pattern\":\"needle\",\"path\":\"sub\"}").getResult();
        assertEquals(1, scoped.get("total_matches").asInt());
        assertEquals("target.txt", scoped.get("matches").get(0).get("relative_path").asText());
    }

    @Test
    void searchFilesSandboxAndSymlinkEscapesBlocked() throws Exception {
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(tempDir.resolve("outside"), outside);

        assertFalse(searchFiles("{\"pattern\":\"secret\",\"path\":\"../../../etc\"}").isSuccess());
        assertFalse(searchFiles("{\"pattern\":\"secret\",\"path\":\"outside\"}").isSuccess());
        var rootScan = searchFiles("{\"pattern\":\"secret\"}");
        assertTrue(rootScan.isSuccess(), rootScan::getError);
        assertEquals(0, rootScan.getResult().get("total_matches").asInt());
    }

    @Test
    void searchFilesMissingPatternReturnsErrorAndLiteralBracketsMatchNothing() throws Exception {
        assertFalse(searchFiles("{}").isSuccess());
        Files.writeString(tempDir.resolve("a.txt"), "needle");
        ToolResult literalBracket = searchFiles("{\"pattern\":\"needle\",\"glob\":\"[unclosed\"}");
        assertTrue(literalBracket.isSuccess(), literalBracket::getError);
        assertEquals(0, literalBracket.getResult().get("total_matches").asInt());
    }

    @Test
    void searchFilesGlobBraceExpansionAndZeroDirectoryDoubleStar() throws Exception {
        Files.writeString(tempDir.resolve("root.java"), "needle");
        Files.writeString(tempDir.resolve("root.txt"), "needle");
        Files.writeString(tempDir.resolve("root.md"), "needle");
        Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/deep.java"), "needle");

        var braces = searchFiles("{\"pattern\":\"needle\",\"glob\":\"*.{java,txt}\"}").getResult();
        assertEquals(2, braces.get("total_matches").asInt());

        var doubleStar = searchFiles("{\"pattern\":\"needle\",\"glob\":\"**/*.java\"}").getResult();
        assertEquals(2, doubleStar.get("total_matches").asInt());
    }

    @Test
    void searchFilesSchemaAdvertisesElectronParameters() {
        PluginManager pm = new PluginManager();
        SearchPlugin.register(pm, tempDir);
        var definition = pm.getDefinitions().stream()
                .filter(tool -> tool.getName().equals("search_files")).findFirst().orElseThrow();
        var properties = definition.getInputSchema().path("properties");
        for (String key : new String[]{"pattern", "path", "glob", "includePatterns", "excludePatterns",
                "caseSensitive", "isRegexp", "includeHidden", "outputMode", "offset", "maxResults"}) {
            assertTrue(properties.has(key), "missing schema property " + key);
        }
        assertEquals("array", properties.path("includePatterns").path("type").asText());
        assertEquals(200, properties.path("maxResults").path("maximum").asInt());
        var required = definition.getInputSchema().path("required");
        assertEquals(1, required.size());
        assertEquals("pattern", required.get(0).asText());
    }
}
