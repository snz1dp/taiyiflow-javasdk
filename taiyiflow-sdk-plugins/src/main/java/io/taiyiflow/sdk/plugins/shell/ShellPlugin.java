package io.taiyiflow.sdk.plugins.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shell command execution plugin with sandbox and whitelist.
 */
public class ShellPlugin implements PluginManager.ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(ShellPlugin.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;

    private final Path workingDirectory;
    private final List<String> allowedCommands;
    private final long timeoutSeconds;

    public ShellPlugin(Path workingDirectory, List<String> allowedCommands) {
        this(workingDirectory, allowedCommands, DEFAULT_TIMEOUT_SECONDS);
    }

    public ShellPlugin(Path workingDirectory, List<String> allowedCommands, long timeoutSeconds) {
        this.workingDirectory = workingDirectory;
        this.allowedCommands = allowedCommands;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public ToolResult execute(ToolCall toolCall) throws Exception {
        JsonNode args = toolCall.getArguments();
        if (args == null || !args.has("command")) {
            return ToolResult.error(toolCall.getId(), "Missing 'command' argument");
        }

        String command = args.get("command").asText();
        if (!isAllowed(command)) {
            return ToolResult.error(toolCall.getId(), "Command not in whitelist: " + command);
        }

        ProcessBuilder pb = new ProcessBuilder();
        pb.command("sh", "-c", command);
        if (workingDirectory != null) {
            pb.directory(workingDirectory.toFile());
        }
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (Exception ignored) {
            }
        });

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(1000);
            return ToolResult.error(toolCall.getId(), "Command timed out after " + timeoutSeconds + "s");
        }

        reader.join(1000);

        int exitCode = process.exitValue();
        ObjectNode result = mapper.createObjectNode();
        result.put("exitCode", exitCode);
        result.put("output", output.toString());

        return ToolResult.success(toolCall.getId(), result);
    }

    private boolean isAllowed(String command) {
        if (allowedCommands == null || allowedCommands.isEmpty()) {
            return false;
        }
        String trimmed = command.trim();
        for (String allowed : allowedCommands) {
            if (trimmed.startsWith(allowed)) {
                return true;
            }
        }
        return false;
    }

    public static void register(PluginManager pm, Path workDir, List<String> allowedCommands) {
        ShellPlugin plugin = new ShellPlugin(workDir, allowedCommands);
        pm.register("shell", plugin);
        pm.register("bash", plugin);
        pm.register("execute_command", plugin);
    }
}
