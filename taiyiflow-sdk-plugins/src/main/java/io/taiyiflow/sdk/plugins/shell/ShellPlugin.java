package io.taiyiflow.sdk.plugins.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.core.process.ManagedProcess;
import io.taiyiflow.sdk.plugins.workspace.WorkspaceAudit;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;

import static io.taiyiflow.sdk.plugins.ToolDefinitions.tool;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shell execution with an executable whitelist and bounded workspace change auditing. */
public class ShellPlugin implements PluginManager.ContextualToolHandler, AutoCloseable {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 1_000_000;
    private static final Set<String> INTERPRETERS = Set.of(
            "python", "python3", "node", "deno", "bun", "bash", "sh", "zsh");
    private static final Set<String> BUILD_COMMANDS = Set.of(
            "npm", "yarn", "pnpm", "mvn", "mvnw", "gradle", "gradlew", "cargo", "go", "make");

    private final Path workingDirectory;
    private final List<String> allowedCommands;
    private final long timeoutSeconds;
    private final WorkspaceAudit workspaceAudit;
    private final BackgroundProcessRegistry backgroundProcesses;

    public ShellPlugin(Path workingDirectory, List<String> allowedCommands) {
        this(workingDirectory, allowedCommands, DEFAULT_TIMEOUT_SECONDS);
    }

    public ShellPlugin(Path workingDirectory, List<String> allowedCommands, long timeoutSeconds) {
        this(workingDirectory, allowedCommands, timeoutSeconds,
                new WorkspaceAudit(workingDirectory));
    }

    public ShellPlugin(Path workingDirectory, List<String> allowedCommands, long timeoutSeconds,
                       WorkspaceAudit.Limits auditLimits) {
        this(workingDirectory, allowedCommands, timeoutSeconds,
                new WorkspaceAudit(workingDirectory, auditLimits));
    }

    public ShellPlugin(Path workingDirectory, List<String> allowedCommands, long timeoutSeconds,
                       WorkspaceAudit workspaceAudit) {
        this(workingDirectory, allowedCommands, timeoutSeconds, workspaceAudit,
                new BackgroundProcessRegistry(64, 65_536, Duration.ofSeconds(2)));
    }

    ShellPlugin(Path workingDirectory, List<String> allowedCommands, long timeoutSeconds,
                WorkspaceAudit workspaceAudit, BackgroundProcessRegistry backgroundProcesses) {
        this.workingDirectory = workingDirectory == null ? null
                : workingDirectory.toAbsolutePath().normalize();
        this.allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
        this.timeoutSeconds = timeoutSeconds;
        this.workspaceAudit = workspaceAudit;
        this.backgroundProcesses = backgroundProcesses;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, ToolCall toolCall) throws Exception {
        JsonNode args = toolCall.getArguments();
        if (args == null || !args.has("command")) {
            return ToolResult.error(toolCall.getId(), "Missing 'command' argument");
        }

        String command = args.get("command").asText();
        List<String> arguments;
        try {
            arguments = tokenize(command);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(toolCall.getId(), e.getMessage());
        }
        if (!isAllowed(arguments)) {
            return ToolResult.error(toolCall.getId(), "Command not in whitelist: " + command);
        }

        Path commandDirectory;
        try {
            commandDirectory = resolveWorkingDirectory(args);
        } catch (IllegalArgumentException | IOException e) {
            return ToolResult.error(toolCall.getId(), e.getMessage());
        }

        List<DeclaredOperation> declared = parseDeclaredOperations(args);
        boolean scanEnabled = shouldScan(args, arguments);
        WorkspaceAudit.Scan baseline = scanEnabled ? captureBaseline(context) : null;

        ProcessBuilder pb = new ProcessBuilder(arguments);
        pb.directory(commandDirectory.toFile());
        if (args.path("background").asBoolean(false)) {
            pb.redirectErrorStream(false);
            Process process = pb.start();
            try {
                BackgroundProcessRegistry.Entry entry = backgroundProcesses.start(
                        process, arguments, commandDirectory, context, workspaceAudit, baseline);
                ObjectNode result = processResult(entry);
                result.put("started", true);
                return ToolResult.success(toolCall.getId(), result);
            } catch (RuntimeException e) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                return ToolResult.error(toolCall.getId(), e.getMessage());
            }
        }
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        AtomicBoolean outputTruncated = new AtomicBoolean();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (Reader r = new InputStreamReader(process.getInputStream())) {
                readOutput(r, output, outputTruncated);
            } catch (Exception ignored) {
            }
        });

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.getInputStream().close();
            reader.join(1000);
            ObjectNode result = baseResult(output, outputTruncated);
            result.put("timedOut", true);
            appendArtifacts(context, declared, baseline, result);
            ToolResult timeout = ToolResult.error(toolCall.getId(),
                    "Command timed out after " + timeoutSeconds + "s");
            timeout.setResult(result);
            return timeout;
        }

        reader.join(1000);
        if (reader.isAlive()) {
            process.getInputStream().close();
            reader.join(1000);
        }

        ObjectNode result = baseResult(output, outputTruncated);
        result.put("exitCode", process.exitValue());
        appendArtifacts(context, declared, baseline, result);
        return ToolResult.success(toolCall.getId(), result);
    }

    public ToolResult processList(ToolExecutionContext context, ToolCall toolCall) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode processes = result.putArray("processes");
        backgroundProcesses.list(context.getSessionId()).forEach(entry -> processes.add(processResult(entry)));
        return ToolResult.success(toolCall.getId(), result);
    }

    public ToolResult processOutput(ToolExecutionContext context, ToolCall toolCall) {
        String handle = handleArgument(toolCall.getArguments());
        BackgroundProcessRegistry.Entry entry = backgroundProcesses.find(handle, context.getSessionId());
        if (entry == null) {
            return lostProcess(toolCall, handle);
        }
        JsonNode args = toolCall.getArguments();
        ObjectNode result = processResult(entry);
        appendOutput(result.putObject("stdout"), entry.process().output(ManagedProcess.OutputStream.STDOUT,
                args.path("stdout_offset").asLong(0)));
        appendOutput(result.putObject("stderr"), entry.process().output(ManagedProcess.OutputStream.STDERR,
                args.path("stderr_offset").asLong(0)));
        appendBackgroundAudit(entry, result);
        return ToolResult.success(toolCall.getId(), result);
    }

    public ToolResult processStop(ToolExecutionContext context, ToolCall toolCall) {
        String handle = handleArgument(toolCall.getArguments());
        BackgroundProcessRegistry.Entry entry = backgroundProcesses.stop(handle, context.getSessionId());
        if (entry == null) {
            return lostProcess(toolCall, handle);
        }
        ObjectNode result = processResult(entry);
        result.put("stop_requested", true);
        appendBackgroundAudit(entry, result);
        return ToolResult.success(toolCall.getId(), result);
    }

    public ToolResult processCleanup(ToolExecutionContext context, ToolCall toolCall) {
        String handle = handleArgument(toolCall.getArguments());
        if (handle == null) {
            ObjectNode result = mapper.createObjectNode();
            result.put("removed", backgroundProcesses.cleanupFinished(context.getSessionId()));
            return ToolResult.success(toolCall.getId(), result);
        }
        BackgroundProcessRegistry.Entry entry = backgroundProcesses.find(handle, context.getSessionId());
        if (entry == null) {
            return lostProcess(toolCall, handle);
        }
        if (entry.process().isAlive()) {
            return ToolResult.error(toolCall.getId(), "Process is still running: " + handle);
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("process_handle", handle);
        result.put("removed", backgroundProcesses.cleanup(handle, context.getSessionId()));
        return ToolResult.success(toolCall.getId(), result);
    }

    private static String handleArgument(JsonNode args) {
        if (args == null) {
            return null;
        }
        JsonNode handle = args.hasNonNull("process_handle")
                ? args.get("process_handle") : args.get("handle");
        return handle == null || handle.asText().isBlank() ? null : handle.asText();
    }

    private static ToolResult lostProcess(ToolCall toolCall, String handle) {
        ToolResult result = ToolResult.error(toolCall.getId(),
                "Unknown or lost process handle: " + (handle == null ? "<missing>" : handle));
        ObjectNode detail = mapper.createObjectNode();
        detail.put("status", "lost");
        detail.put("error", "unknown_or_lost");
        if (handle != null) {
            detail.put("process_handle", handle);
        }
        result.setResult(detail);
        return result;
    }

    private static void appendOutput(ObjectNode target, ManagedProcess.OutputChunk chunk) {
        target.put("data", chunk.data());
        target.put("next_offset", chunk.nextOffset());
        target.put("dropped_chars", chunk.droppedChars());
        target.put("offset_reset", chunk.offsetReset());
    }

    private static ObjectNode processResult(BackgroundProcessRegistry.Entry entry) {
        ObjectNode result = mapper.createObjectNode();
        result.put("process_handle", entry.handle());
        result.put("status", entry.status());
        ArrayNode command = result.putArray("command");
        entry.command().forEach(command::add);
        result.put("cwd", entry.cwd().toString());
        result.put("session_id", entry.sessionId());
        if (entry.runId() != null) {
            result.put("run_id", entry.runId());
        }
        if (entry.taskId() != null) {
            result.put("task_id", entry.taskId());
        }
        result.put("created_at", entry.createdAt().toString());
        if (entry.process().endedAt() != null) {
            result.put("ended_at", entry.process().endedAt().toString());
        }
        if (entry.process().exitCode() == null) {
            result.putNull("exit_code");
        } else {
            result.put("exit_code", entry.process().exitCode());
        }
        return result;
    }

    private Path resolveWorkingDirectory(JsonNode args) throws IOException {
        Path base = workingDirectory == null ? Path.of("").toAbsolutePath().normalize() : workingDirectory;
        Path candidate = args.hasNonNull("cwd") ? Path.of(args.get("cwd").asText()) : base;
        if (!candidate.isAbsolute()) {
            candidate = base.resolve(candidate);
        }
        candidate = candidate.normalize();
        if (!candidate.startsWith(base)) {
            throw new IllegalArgumentException("Working directory escapes the shell sandbox");
        }
        Path realBase = base.toRealPath();
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realBase) || !Files.isDirectory(realCandidate)) {
            throw new IllegalArgumentException("Working directory escapes the shell sandbox");
        }
        return realCandidate;
    }

    private static ObjectNode baseResult(StringBuilder output, AtomicBoolean outputTruncated) {
        ObjectNode result = mapper.createObjectNode();
        result.put("output", output.toString());
        result.put("outputTruncated", outputTruncated.get());
        return result;
    }

    private WorkspaceAudit.Scan captureBaseline(ToolExecutionContext context) {
        try {
            return workspaceAudit.captureBaseline(context.getSessionId(), context.getRunId());
        } catch (RuntimeException error) {
            return new WorkspaceAudit.Scan(false, List.of("SCAN_IO_ERROR"), Map.of(), 0);
        }
    }

    /** Merge declared and discovered artifacts into the existing file_operations result channel. */
    private void appendArtifacts(ToolExecutionContext context, List<DeclaredOperation> declared,
                                 WorkspaceAudit.Scan baseline, ObjectNode result) {
        Map<String, ObjectNode> operations = new LinkedHashMap<>();
        Set<Path> declaredPaths = new HashSet<>();
        int declaredCount = 0;
        for (DeclaredOperation operation : declared) {
            if (operation.path() != null) {
                declaredPaths.add(operation.path());
            }
            if (operation.newPath() != null) {
                declaredPaths.add(operation.newPath());
            }
            if (!operation.artifact() || operation.path() == null) {
                continue;
            }
            declaredCount++;
            ObjectNode output = mapper.createObjectNode();
            output.put("type", operation.type());
            output.put("path", operation.path().toString());
            if (operation.newPath() != null) {
                output.put("newPath", operation.newPath().toString());
            }
            addMetadata("rename".equals(operation.type()) && operation.newPath() != null
                    ? operation.newPath() : operation.path(), output);
            operations.putIfAbsent(operation.type() + '|' + operation.path() + '|'
                    + operation.newPath(), output);
        }

        if (baseline != null) {
            WorkspaceAudit.AuditResult audit;
            try {
                audit = workspaceAudit.diffAndRefresh(context.getSessionId(), context.getRunId(),
                        baseline, declaredPaths);
            } catch (RuntimeException error) {
                audit = new WorkspaceAudit.AuditResult(false, List.of("SCAN_IO_ERROR"), List.of(), 0);
            }
            int scannedChanges = 0;
            for (WorkspaceAudit.Change change : audit.changes()) {
                String key = change.type() + '|' + change.path() + "|null";
                if (operations.containsKey(key)) {
                    continue;
                }
                ObjectNode output = mapper.createObjectNode();
                output.put("type", change.type());
                output.put("path", change.path().toString());
                putNullable(output, "size", change.size());
                putNullable(output, "mtime", change.mtime());
                if (change.sha256() != null) {
                    output.put("sha256", change.sha256());
                }
                output.put("source", "workspace_scan");
                operations.put(key, output);
                scannedChanges++;
            }
            ObjectNode auditNode = result.putObject("workspace_audit");
            auditNode.put("enabled", true);
            auditNode.put("complete", audit.complete());
            ArrayNode reasons = auditNode.putArray("degradation_reasons");
            audit.degradationReasons().forEach(reasons::add);
            auditNode.put("scanned_entries", audit.scannedEntries());
            auditNode.put("scan_changes", scannedChanges);
            auditNode.put("declared_operations", declaredCount);
            auditNode.put("note", WorkspaceAudit.AUDIT_NOTE);
        }
        if (!operations.isEmpty()) {
            ArrayNode fileOperations = result.putArray("file_operations");
            operations.values().forEach(fileOperations::add);
        }
    }

    private static void appendBackgroundAudit(BackgroundProcessRegistry.Entry entry, ObjectNode result) {
        WorkspaceAudit.AuditResult audit = entry.auditResult();
        if (audit == null) {
            return;
        }
        ArrayNode operations = mapper.createArrayNode();
        for (WorkspaceAudit.Change change : audit.changes()) {
            ObjectNode output = operations.addObject();
            output.put("type", change.type());
            output.put("path", change.path().toString());
            putNullable(output, "size", change.size());
            putNullable(output, "mtime", change.mtime());
            if (change.sha256() != null) {
                output.put("sha256", change.sha256());
            }
            output.put("source", "workspace_scan");
        }
        if (!operations.isEmpty()) {
            result.set("file_operations", operations);
        }
        ObjectNode auditNode = result.putObject("workspace_audit");
        auditNode.put("enabled", true);
        auditNode.put("complete", audit.complete());
        ArrayNode reasons = auditNode.putArray("degradation_reasons");
        audit.degradationReasons().forEach(reasons::add);
        auditNode.put("scanned_entries", audit.scannedEntries());
        auditNode.put("scan_changes", operations.size());
        auditNode.put("declared_operations", 0);
        auditNode.put("note", WorkspaceAudit.AUDIT_NOTE);
    }

    private static void putNullable(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void addMetadata(Path path, ObjectNode output) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isRegularFile()) {
                output.put("size", attributes.size());
                output.put("mtime", attributes.lastModifiedTime().toMillis());
            }
        } catch (IOException | SecurityException ignored) {
            // A declared read/delete target may no longer exist; retain the declaration itself.
        }
    }

    private List<DeclaredOperation> parseDeclaredOperations(JsonNode args) {
        JsonNode declarations = args.has("fileOperations")
                ? args.get("fileOperations") : args.get("file_operations");
        if (declarations == null || !declarations.isArray()) {
            return List.of();
        }
        List<DeclaredOperation> result = new ArrayList<>();
        for (JsonNode declaration : declarations) {
            if (!declaration.hasNonNull("type") || !declaration.hasNonNull("path")) {
                continue;
            }
            String newPathText = declaration.hasNonNull("newPath")
                    ? declaration.get("newPath").asText()
                    : declaration.path("new_path").asText(null);
            result.add(new DeclaredOperation(declaration.get("type").asText(),
                    safeDeclaredPath(declaration.get("path").asText()),
                    newPathText == null ? null : safeDeclaredPath(newPathText),
                    declaration.path("is_artifact").asBoolean(true)));
        }
        return result;
    }

    /** Resolve declarations without following a final symlink or leaving the audit sandbox. */
    private Path safeDeclaredPath(String value) {
        try {
            Path root = workspaceAudit.root();
            Path candidate = Path.of(value);
            Path normalized = (candidate.isAbsolute() ? candidate : root.resolve(candidate))
                    .toAbsolutePath().normalize();
            if (!normalized.startsWith(root) || Files.isSymbolicLink(normalized)) {
                return null;
            }
            Path ancestor = normalized;
            while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                ancestor = ancestor.getParent();
            }
            if (ancestor == null) {
                return null;
            }
            Path realAncestor = ancestor.toRealPath();
            Path realRoot = root.toRealPath();
            if (!realAncestor.startsWith(realRoot)) {
                return null;
            }
            return realAncestor.resolve(ancestor.relativize(normalized)).normalize();
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static boolean shouldScan(JsonNode args, List<String> arguments) {
        if (args.has("scan_workspace")) {
            return args.get("scan_workspace").asBoolean(false);
        }
        if (args.has("scanWorkspace")) {
            return args.get("scanWorkspace").asBoolean(false);
        }
        if (arguments.isEmpty()) {
            return false;
        }
        String executable = Path.of(arguments.get(0)).getFileName().toString()
                .toLowerCase(Locale.ROOT);
        if (INTERPRETERS.contains(executable)) {
            return true;
        }
        if (BUILD_COMMANDS.contains(executable)) {
            return arguments.stream().skip(1).map(value -> value.toLowerCase(Locale.ROOT))
                    .anyMatch(value -> Set.of("run", "build", "install", "test", "generate",
                            "compile", "package").contains(value));
        }
        return false;
    }

    static void readOutput(Reader reader, StringBuilder output, AtomicBoolean outputTruncated)
            throws IOException {
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            int remaining = MAX_OUTPUT_CHARS - output.length();
            if (remaining > 0) {
                output.append(buffer, 0, Math.min(read, remaining));
            }
            if (read > remaining) {
                outputTruncated.set(true);
            }
        }
    }

    private boolean isAllowed(List<String> arguments) {
        return !arguments.isEmpty() && allowedCommands.contains(arguments.get(0));
    }

    private static List<String> tokenize(String command) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        boolean started = false;
        for (int i = 0; i < command.length(); i++) {
            char character = command.charAt(i);
            if (escaped) {
                current.append(character);
                escaped = false;
                started = true;
            } else if (character == '\\' && quote != '\'') {
                escaped = true;
                started = true;
            } else if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                } else {
                    current.append(character);
                }
                started = true;
            } else if (character == '\'' || character == '"') {
                quote = character;
                started = true;
            } else if (Character.isWhitespace(character)) {
                if (started) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else {
                current.append(character);
                started = true;
            }
        }
        if (quote != 0 || escaped) {
            throw new IllegalArgumentException("Invalid command: unterminated quote or escape");
        }
        if (started) {
            arguments.add(current.toString());
        }
        return arguments;
    }

    @Override
    public void close() {
        backgroundProcesses.close();
        workspaceAudit.clear(null, null);
    }

    public static void register(PluginManager pm, Path workDir, List<String> allowedCommands) {
        ShellPlugin plugin = new ShellPlugin(workDir, allowedCommands);
        String commandSchema = "{\"command\":{\"type\":\"string\"},\"cwd\":{\"type\":\"string\"},"
                + "\"background\":{\"type\":\"boolean\"}}";
        pm.register(tool("shell", "Execute a whitelisted command in the sandbox", commandSchema, "command"), plugin);
        pm.register(tool("bash", "Execute a whitelisted command in the sandbox", commandSchema, "command"), plugin);
        pm.register(tool("execute_command", "Execute a whitelisted command in the sandbox", commandSchema,
                "command"), plugin);
        pm.register(tool("shell_exec", "Execute a whitelisted command in the sandbox", commandSchema,
                "command"), plugin);
        pm.register(tool("process_list", "List local background processes for this session", "{}"),
                (PluginManager.ContextualToolHandler) plugin::processList);
        String outputSchema = "{\"process_handle\":{\"type\":\"string\"},\"handle\":{\"type\":\"string\"},"
                + "\"stdout_offset\":{\"type\":\"integer\"},\"stderr_offset\":{\"type\":\"integer\"}}";
        pm.register(tool("process_output", "Read local background process output", outputSchema),
                (PluginManager.ContextualToolHandler) plugin::processOutput);
        String handleSchema = "{\"process_handle\":{\"type\":\"string\"},\"handle\":{\"type\":\"string\"}}";
        pm.register(tool("process_stop", "Stop a local background process", handleSchema),
                (PluginManager.ContextualToolHandler) plugin::processStop);
        pm.register(tool("process_cleanup", "Clean up local background process handles", handleSchema),
                (PluginManager.ContextualToolHandler) plugin::processCleanup);
        pm.register(tool("task_list", "List local SDK background process handles for this session", "{}"),
                (PluginManager.ContextualToolHandler) plugin::processList);
        pm.register(tool("task_output", "Read local SDK background process output", outputSchema),
                (PluginManager.ContextualToolHandler) plugin::processOutput);
        pm.register(tool("task_stop", "Stop a local SDK background process", handleSchema),
                (PluginManager.ContextualToolHandler) plugin::processStop);
        pm.register(tool("task_delete", "Delete a finished local SDK process handle", handleSchema),
                (PluginManager.ContextualToolHandler) plugin::processCleanup);
        pm.register(tool("task_cleanup", "Clean up local SDK background process handles", handleSchema),
                (PluginManager.ContextualToolHandler) plugin::processCleanup);
        pm.registerSessionHook(new io.taiyiflow.sdk.hooks.SessionHook() {
            @Override
            public void onClose(String sessionId) {
                plugin.backgroundProcesses.stopSession(sessionId);
                plugin.backgroundProcesses.cleanupFinished(sessionId);
            }
        });
        pm.registerCloseable(plugin);
    }

    private record DeclaredOperation(String type, Path path, Path newPath, boolean artifact) {
    }
}
