package io.taiyiflow.sdk.plugins.shell;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ShellPluginTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private ToolCall makeCall(String command) throws Exception {
        return new ToolCall("tc-1", "shell", mapper.readTree("{\"command\":\"" + command + "\"}"));
    }

    @Test
    void allowedCommandExecutes() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("echo"), 10);
        ToolResult result = plugin.execute(makeCall("echo hello"));
        assertTrue(result.isSuccess());
        assertTrue(result.getResult().get("output").asText().contains("hello"));
        assertEquals(0, result.getResult().get("exitCode").asInt());
    }

    @Test
    void shellOperatorsCannotBypassWhitelist() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("echo"), 10);
        Path marker = tempDir.resolve("marker");

        ToolResult result = plugin.execute(makeCall("echo safe; touch " + marker));

        assertTrue(result.isSuccess());
        assertFalse(Files.exists(marker));
        assertTrue(result.getResult().get("output").asText().contains("safe; touch"));
    }

    @Test
    void executableNameMustMatchExactly() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("echo"), 10);

        ToolResult result = plugin.execute(makeCall("echo-malicious"));

        assertFalse(result.isSuccess());
    }

    @Test
    void disallowedCommandRejected() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("echo"), 10);
        ToolResult result = plugin.execute(makeCall("rm -rf /"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("not in whitelist"));
    }

    @Test
    void emptyWhitelistRejectsAll() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of(), 10);
        ToolResult result = plugin.execute(makeCall("echo test"));
        assertFalse(result.isSuccess());
    }

    @Test
    void missingCommandArgumentReturnsError() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("echo"), 10);
        ToolCall call = new ToolCall("tc-1", "shell", mapper.createObjectNode());
        ToolResult result = plugin.execute(call);
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("Missing 'command'"));
    }

    @Test
    void nullArgumentsReturnsError() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("echo"), 10);
        ToolCall call = new ToolCall("tc-1", "shell", null);
        ToolResult result = plugin.execute(call);
        assertFalse(result.isSuccess());
    }

    @Test
    void commandWithNonZeroExitCode() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("ls"), 10);
        ToolResult result = plugin.execute(makeCall("ls /nonexistent_path_xyz_12345"));
        assertTrue(result.isSuccess());
        assertNotEquals(0, result.getResult().get("exitCode").asInt());
    }

    @Test
    void workingDirectoryRespected() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("pwd"), 10);
        ToolResult result = plugin.execute(makeCall("pwd"));
        assertTrue(result.isSuccess());
        assertTrue(result.getResult().get("output").asText().contains(tempDir.getFileName().toString()));
    }

    @Test
    void unterminatedLongLineIsTruncatedWithFixedSizeReads() throws Exception {
        StringBuilder output = new StringBuilder();
        AtomicBoolean truncated = new AtomicBoolean();

        ShellPlugin.readOutput(new StringReader("x".repeat(1_000_100)), output, truncated);

        assertEquals(1_000_000, output.length());
        assertTrue(truncated.get());
    }

    @Test
    void registerAddsMultipleAliases() {
        PluginManager pm = new PluginManager();
        ShellPlugin.register(pm, tempDir, List.of("echo"));
        assertTrue(pm.hasHandler("shell"));
        assertTrue(pm.hasHandler("bash"));
        assertTrue(pm.hasHandler("execute_command"));
    }

    @Test
    void commandTimeout() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("sleep"), 1);
        ToolResult result = plugin.execute(makeCall("sleep 60"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("timed out"));
    }
}
