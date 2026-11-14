package io.taiyiflow.sdk.plugins.shell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.plugins.workspace.WorkspaceAudit;
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

    private ToolCall scanCall(String command, String declaredPath, boolean artifact) {
        ObjectNode args = mapper.createObjectNode();
        args.put("command", command);
        args.put("scan_workspace", true);
        if (declaredPath != null) {
            ObjectNode declaration = args.putArray("fileOperations").addObject();
            declaration.put("type", "create");
            declaration.put("path", declaredPath);
            declaration.put("is_artifact", artifact);
        }
        ToolCall call = new ToolCall("tc-1", "shell", args);
        call.setSessionId("session-1");
        call.setRunId("run-1");
        return call;
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
    void workspaceScanReportsUndeclaredArtifactMetadata() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("touch"), 10);

        ToolResult result = plugin.execute(scanCall("touch artifact.txt", null, true));

        assertTrue(result.isSuccess(), result::getError);
        assertTrue(result.getResult().get("workspace_audit").get("complete").asBoolean());
        assertTrue(result.getResult().get("workspace_audit").get("note").asText()
                .contains("not a complete transaction record"));
        assertEquals(1, result.getResult().get("file_operations").size());
        var artifact = result.getResult().get("file_operations").get(0);
        assertEquals("create", artifact.get("type").asText());
        assertEquals(tempDir.resolve("artifact.txt").toRealPath().toString(),
                artifact.get("path").asText());
        assertEquals(0, artifact.get("size").asLong());
        assertTrue(artifact.has("mtime"));
        assertEquals(64, artifact.get("sha256").asText().length());
        assertEquals("workspace_scan", artifact.get("source").asText());
    }

    @Test
    void declaredAndScannedArtifactsAreDeduplicated() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("touch"), 10);

        ToolResult result = plugin.execute(scanCall("touch declared.txt", "declared.txt", true));

        assertTrue(result.isSuccess(), result::getError);
        assertEquals(1, result.getResult().get("file_operations").size());
        assertEquals(1, result.getResult().get("workspace_audit")
                .get("declared_operations").asInt());
        assertEquals(0, result.getResult().get("workspace_audit").get("scan_changes").asInt());
    }

    @Test
    void nonArtifactDeclarationIsAlsoExcludedFromScan() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("touch"), 10);

        ToolResult result = plugin.execute(scanCall("touch temporary.txt", "temporary.txt", false));

        assertTrue(result.isSuccess(), result::getError);
        assertFalse(result.getResult().has("file_operations"));
        assertTrue(result.getResult().get("workspace_audit").get("complete").asBoolean());
    }

    @Test
    void refreshedBaselineDoesNotReportSameArtifactTwice() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("touch", "echo"), 10);
        ToolResult first = plugin.execute(scanCall("touch once.txt", null, true));
        assertTrue(first.isSuccess(), first::getError);
        assertEquals(1, first.getResult().get("file_operations").size());

        ToolResult second = plugin.execute(scanCall("echo done", null, true));

        assertTrue(second.isSuccess(), second::getError);
        assertTrue(second.getResult().get("workspace_audit").get("complete").asBoolean());
        assertFalse(second.getResult().has("file_operations"));
    }

    @Test
    void scanBudgetDegradationIsExplicitAndDoesNotFakeNoChanges() throws Exception {
        Files.writeString(tempDir.resolve("one"), "1");
        Files.writeString(tempDir.resolve("two"), "2");
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("echo"), 10,
                new WorkspaceAudit.Limits(4, 1, 5_000, 4, 1024));

        ToolResult result = plugin.execute(scanCall("echo done", null, true));

        assertTrue(result.isSuccess(), result::getError);
        var audit = result.getResult().get("workspace_audit");
        assertFalse(audit.get("complete").asBoolean());
        assertEquals("MAX_ENTRIES_EXCEEDED", audit.get("degradation_reasons").get(0).asText());
        assertFalse(result.getResult().has("file_operations"));
    }

    @Test
    void backgroundProcessSupportsIncrementalOutputAndCleanup() throws Exception {
        try (ShellPlugin plugin = new ShellPlugin(tempDir, List.of("sh"), 10)) {
            ToolExecutionContext context = context("session-background", new ToolExecutionContext.CancellationSource());
            ToolResult started = plugin.execute(context, backgroundCall(
                    "sh -c 'printf first; sleep 0.2; printf second; printf error >&2'", false));
            assertTrue(started.isSuccess(), started::getError);
            String handle = started.getResult().get("process_handle").asText();
            assertTrue(handle.startsWith("proc_"));

            JsonNode initial = awaitOutput(plugin, context, handle, "first");
            long stdoutOffset = initial.get("stdout").get("next_offset").asLong();
            JsonNode finished = awaitTerminal(plugin, context, handle);
            assertEquals(0, finished.get("exit_code").asInt());
            assertTrue(finished.get("stdout").get("data").asText().contains("second"));
            assertTrue(finished.get("stderr").get("data").asText().contains("error"));
            assertEquals("exited", finished.get("status").asText());
            assertTrue(stdoutOffset > 0);

            ToolResult cleanup = plugin.processCleanup(context, processCall("process_cleanup", handle));
            assertTrue(cleanup.getResult().get("removed").asBoolean());
            assertFalse(plugin.processOutput(context, processCall("process_output", handle)).isSuccess());
        }
    }

    @Test
    void backgroundOutputIsContinuouslyDrainedAndBounded() throws Exception {
        try (ShellPlugin plugin = new ShellPlugin(tempDir, List.of("python3"), 10)) {
            ToolExecutionContext context = context("session-large-output", new ToolExecutionContext.CancellationSource());
            ToolResult started = plugin.execute(context, backgroundCall(
                    "python3 -c 'import sys; sys.stdout.write(\"x\" * 200000)'", false));
            assertTrue(started.isSuccess(), started::getError);
            String handle = started.getResult().get("process_handle").asText();

            JsonNode finished = awaitTerminal(plugin, context, handle);
            assertEquals(0, finished.get("exit_code").asInt());
            JsonNode stdout = finished.get("stdout");
            assertTrue(stdout.get("offset_reset").asBoolean());
            assertTrue(stdout.get("dropped_chars").asLong() > 0);
            assertEquals(65_536, stdout.get("data").asText().length());
            assertEquals(200_000, stdout.get("next_offset").asLong());
        }
    }

    @Test
    void stopTerminatesRegisteredProcessTree() throws Exception {
        try (ShellPlugin plugin = new ShellPlugin(tempDir, List.of("sh"), 10)) {
            ToolExecutionContext context = context("session-tree", new ToolExecutionContext.CancellationSource());
            ToolResult started = plugin.execute(context, backgroundCall(
                    "sh -c 'sleep 60 & child=$!; echo $child > child.pid; wait'", false));
            assertTrue(started.isSuccess(), started::getError);
            String handle = started.getResult().get("process_handle").asText();
            long childPid = awaitPid(tempDir.resolve("child.pid"));

            ToolResult stopped = plugin.processStop(context, processCall("process_stop", handle));
            assertTrue(stopped.isSuccess(), stopped::getError);
            assertTrue(stopped.getResult().get("stop_requested").asBoolean());
            awaitProcessExit(childPid);
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void staleAndCrossSessionHandlesNeverTargetOperatingSystemPids() throws Exception {
        ShellPlugin first = new ShellPlugin(tempDir, List.of("sh"), 10);
        ToolExecutionContext context = context("session-owner", new ToolExecutionContext.CancellationSource());
        ToolResult started = first.execute(context, backgroundCall("sh -c 'sleep 60'", false));
        String handle = started.getResult().get("process_handle").asText();

        ToolResult foreign = first.processStop(context("session-other",
                new ToolExecutionContext.CancellationSource()), processCall("process_stop", handle));
        assertFalse(foreign.isSuccess());
        assertEquals("lost", foreign.getResult().get("status").asText());
        first.close();

        try (ShellPlugin restarted = new ShellPlugin(tempDir, List.of("sh"), 10)) {
            ToolResult stale = restarted.processOutput(context, processCall("process_output", handle));
            assertFalse(stale.isSuccess());
            assertEquals("unknown_or_lost", stale.getResult().get("error").asText());
            assertTrue(restarted.processList(context, processCall("process_list", null))
                    .getResult().get("processes").isEmpty());
        }
    }

    @Test
    void cancellationAndPluginCloseStopBackgroundProcesses() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("sh"), 10);
        ToolExecutionContext.CancellationSource cancellation = new ToolExecutionContext.CancellationSource();
        ToolExecutionContext context = context("session-cancel", cancellation);
        ToolResult started = plugin.execute(context, backgroundCall(
                "sh -c 'sleep 60 & child=$!; echo $child > cancel.pid; wait'", false));
        String handle = started.getResult().get("process_handle").asText();
        long childPid = awaitPid(tempDir.resolve("cancel.pid"));

        cancellation.cancel();
        awaitProcessExit(childPid);
        assertEquals("stopped", awaitTerminal(plugin, context, handle).get("status").asText());
        plugin.close();
        assertFalse(plugin.processOutput(context, processCall("process_output", handle)).isSuccess());
    }

    @Test
    void backgroundExitRefreshesWorkspaceArtifactAudit() throws Exception {
        try (ShellPlugin plugin = new ShellPlugin(tempDir, List.of("sh"), 10)) {
            ToolExecutionContext context = context("session-artifact", new ToolExecutionContext.CancellationSource());
            ToolResult started = plugin.execute(context, backgroundCall(
                    "sh -c 'printf artifact > background.txt'", true));
            String handle = started.getResult().get("process_handle").asText();

            JsonNode finished = awaitTerminal(plugin, context, handle);
            assertEquals(0, finished.get("exit_code").asInt());
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!finished.has("workspace_audit") && System.nanoTime() < deadline) {
                Thread.sleep(20);
                finished = plugin.processOutput(context, processCall("process_output", handle)).getResult();
            }
            assertTrue(finished.path("workspace_audit").path("complete").asBoolean());
            assertEquals(1, finished.path("file_operations").size());
            assertEquals("create", finished.get("file_operations").get(0).get("type").asText());
            assertEquals(tempDir.resolve("background.txt").toRealPath().toString(),
                    finished.get("file_operations").get(0).get("path").asText());
        }
    }

    @Test
    void registerAddsMultipleAliases() {
        PluginManager pm = new PluginManager();
        ShellPlugin.register(pm, tempDir, List.of("echo"));
        assertTrue(pm.hasHandler("shell"));
        assertTrue(pm.hasHandler("bash"));
        assertTrue(pm.hasHandler("execute_command"));
        assertTrue(pm.hasHandler("shell_exec"));
        for (String alias : new String[]{"task_list", "task_output", "task_stop", "task_delete", "task_cleanup"}) {
            assertTrue(pm.hasHandler(alias));
        }
        pm.close();
    }

    @Test
    void commandTimeout() throws Exception {
        ShellPlugin plugin = new ShellPlugin(tempDir, List.of("sleep"), 1);
        ToolResult result = plugin.execute(makeCall("sleep 60"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("timed out"));
    }

    private ToolCall backgroundCall(String command, boolean scan) {
        ObjectNode args = mapper.createObjectNode();
        args.put("command", command);
        args.put("background", true);
        args.put("scan_workspace", scan);
        return new ToolCall("background-call", "shell", args);
    }

    private ToolCall processCall(String name, String handle) {
        ObjectNode args = mapper.createObjectNode();
        if (handle != null) {
            args.put("process_handle", handle);
        }
        return new ToolCall("process-call", name, args);
    }

    private ToolExecutionContext context(String sessionId,
                                         ToolExecutionContext.CancellationSource cancellation) {
        return ToolExecutionContext.builder("flow-test", sessionId)
                .runId("run-" + sessionId)
                .taskId("task-" + sessionId)
                .workspace(tempDir)
                .cancellation(cancellation)
                .build();
    }

    private JsonNode awaitOutput(ShellPlugin plugin, ToolExecutionContext context,
                                 String handle, String expected) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        JsonNode result;
        do {
            result = plugin.processOutput(context, processCall("process_output", handle)).getResult();
            if (result.get("stdout").get("data").asText().contains(expected)) {
                return result;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Timed out waiting for process output: " + result);
        return result;
    }

    private JsonNode awaitTerminal(ShellPlugin plugin, ToolExecutionContext context,
                                   String handle) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        JsonNode result;
        do {
            result = plugin.processOutput(context, processCall("process_output", handle)).getResult();
            if (!result.get("exit_code").isNull()) {
                return result;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Timed out waiting for process exit: " + result);
        return result;
    }

    private long awaitPid(Path path) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.exists(path) && Files.size(path) > 0) {
                return Long.parseLong(Files.readString(path).trim());
            }
            Thread.sleep(20);
        }
        fail("Timed out waiting for child PID file");
        return -1;
    }

    private void awaitProcessExit(long pid) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            if (System.nanoTime() >= deadline) {
                fail("Child process is still alive: " + pid);
            }
            Thread.sleep(20);
        }
    }
}
