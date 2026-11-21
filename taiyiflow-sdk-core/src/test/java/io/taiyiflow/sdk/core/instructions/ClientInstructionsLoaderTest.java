package io.taiyiflow.sdk.core.instructions;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientInstructionsLoaderTest {

    @TempDir
    Path temp;

    @Test
    void emptyOrMissingDirectoriesProduceNoPackage() throws Exception {
        assertNull(ClientInstructionsLoader.load(null));
        assertNull(ClientInstructionsLoader.load(List.of()));
        assertNull(ClientInstructionsLoader.load(List.of(temp.resolve("missing"))));
        Files.createDirectories(temp.resolve("empty"));
        assertNull(ClientInstructionsLoader.load(List.of(temp.resolve("empty"))));
    }

    @Test
    void discoversKnownFilesAndLocalVariantsWithElectronMergeFormat() throws Exception {
        Files.writeString(temp.resolve("AGENTS.md"), "project rule");
        Files.writeString(temp.resolve("AGENTS.local.md"), "local rule");
        Files.createDirectories(temp.resolve(".github"));
        Files.writeString(temp.resolve(".github/copilot-instructions.md"), "copilot rule");
        Files.writeString(temp.resolve("CLAUDE.md"), "  claude rule  ");
        Files.writeString(temp.resolve("UNRELATED.md"), "must not leak");

        JsonNode pkg = ClientInstructionsLoader.load(List.of(temp));
        assertNotNull(pkg);
        JsonNode layers = pkg.required("layers");
        assertEquals(4, layers.size());
        assertEquals("project", layers.get(0).required("scope").asText());
        assertEquals(20, layers.get(0).required("priority").asInt());
        assertEquals("CLAUDE.md", layers.get(0).required("filePath").asText());
        assertEquals("claude rule", layers.get(0).required("content").asText());
        assertFalse(layers.get(0).has("position"));
        JsonNode localLayer = layers.get(3);
        assertEquals("local", localLayer.required("scope").asText());
        assertEquals(25, localLayer.required("priority").asInt());
        assertEquals("AGENTS.local.md", localLayer.required("filePath").asText());

        String merged = pkg.required("merged").asText();
        assertTrue(merged.startsWith("[Instructions from CLAUDE.md (project, priority 20)]\nclaude rule"));
        assertTrue(merged.contains("[Instructions from AGENTS.md (project, priority 20)]\nproject rule"));
        assertTrue(merged.contains("[Instructions from .github/copilot-instructions.md (project, priority 20)]\ncopilot rule"));
        assertTrue(merged.endsWith("[Instructions from AGENTS.local.md (local, priority 25)]\nlocal rule"));
        assertFalse(merged.contains("UNRELATED"));
        assertFalse(merged.contains(temp.toAbsolutePath().toString()));
        assertEquals(4, merged.split("\n\n").length);
    }

    @Test
    void multipleDirectoriesKeepDeclarationOrderWithinPriority() throws Exception {
        Path first = temp.resolve("first");
        Path second = temp.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(first.resolve("AGENTS.md"), "first");
        Files.writeString(second.resolve("AGENTS.md"), "second");

        JsonNode pkg = ClientInstructionsLoader.load(List.of(first, second));
        String merged = pkg.required("merged").asText();
        assertTrue(merged.indexOf("first") < merged.indexOf("second"));
        assertEquals(2, pkg.required("layers").size());
    }

    @Test
    void oversizedFileIsSkipped() throws Exception {
        Files.writeString(temp.resolve("AGENTS.md"), "x".repeat((int) ClientInstructionsLoader.MAX_FILE_SIZE + 1));
        Files.writeString(temp.resolve("CLAUDE.md"), "small");
        JsonNode pkg = ClientInstructionsLoader.load(List.of(temp));
        assertEquals(1, pkg.required("layers").size());
        assertEquals("CLAUDE.md", pkg.required("layers").get(0).required("filePath").asText());
    }

    @Test
    void totalSizeLimitStopsAddingLayers() throws Exception {
        Path first = temp.resolve("first");
        Path second = temp.resolve("second");
        Files.createDirectories(first.resolve(".github"));
        Files.createDirectories(second);
        String pad = "x".repeat((int) ClientInstructionsLoader.MAX_FILE_SIZE - 20);
        Files.writeString(first.resolve("CLAUDE.md"), "first-claude" + pad);
        Files.writeString(first.resolve("AGENTS.md"), "first-agents" + pad);
        Files.writeString(first.resolve(".github/copilot-instructions.md"), "first-copilot" + pad);
        Files.writeString(second.resolve("CLAUDE.md"), "second-claude" + pad);
        Files.writeString(second.resolve("AGENTS.md"), "second-agents" + pad);

        JsonNode pkg = ClientInstructionsLoader.load(List.of(first, second));
        assertEquals(4, pkg.required("layers").size());
        String merged = pkg.required("merged").asText();
        assertTrue(merged.contains("first-claude"));
        assertTrue(merged.contains("first-agents"));
        assertTrue(merged.contains("first-copilot"));
        assertTrue(merged.contains("second-claude"));
        assertFalse(merged.contains("second-agents"));
    }

    @Test
    void blankFilesAreIgnored() throws Exception {
        Files.writeString(temp.resolve("AGENTS.md"), "   \n  ");
        assertNull(ClientInstructionsLoader.load(List.of(temp)));
    }
}