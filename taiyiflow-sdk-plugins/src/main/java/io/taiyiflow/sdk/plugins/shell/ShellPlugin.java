package io.taiyiflow.sdk.plugins.shell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shell command execution plugin with a working directory and executable whitelist.
 */
public class ShellPlugin implements PluginManager.ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(ShellPlugin.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 1_000_000;

    private final Path workingDirectory;
    private final List<String> allowedCommands;
    private final long timeoutSeconds;

    public ShellPlugin(Path workingDirectory, List<String> allowedCommands) {
        this(workingDirectory, allowedCommands, DEFAULT_TIMEOUT_SECONDS);
    }

    public ShellPlugin(Path workingDirectory, List<String> allowedCommands, long timeoutSeconds) {
        this.workingDirectory = workingDirectory;
        this.allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public ToolResult execute(ToolCall toolCall) throws Exception {
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

        ProcessBuilder pb = new ProcessBuilder(arguments);
        if (workingDirectory != null) {
            pb.directory(workingDirectory.toFile());
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
            return ToolResult.error(toolCall.getId(), "Command timed out after " + timeoutSeconds + "s");
        }

        reader.join(1000);
        if (reader.isAlive()) {
            process.getInputStream().close();
            reader.join(1000);
        }

        int exitCode = process.exitValue();
        ObjectNode result = mapper.createObjectNode();
        result.put("exitCode", exitCode);
        result.put("output", output.toString());
        result.put("outputTruncated", outputTruncated.get());

        return ToolResult.success(toolCall.getId(), result);
    }

    static void readOutput(Reader reader, StringBuilder output, AtomicBoolean outputTruncated) throws IOException {
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

    public static void register(PluginManager pm, Path workDir, List<String> allowedCommands) {
        ShellPlugin plugin = new ShellPlugin(workDir, allowedCommands);
        pm.register("shell", plugin);
        pm.register("bash", plugin);
        pm.register("execute_command", plugin);
    }
}
