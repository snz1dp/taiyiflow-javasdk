package io.taiyiflow.sdk.plugins.files;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * File read/write plugin with sandbox directory restriction.
 */
public class FilePlugin {

    private static final Logger log = LoggerFactory.getLogger(FilePlugin.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Path sandboxRoot;

    public FilePlugin(Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot.toAbsolutePath().normalize();
    }

    public ToolResult readFile(ToolCall toolCall) {
        JsonNode args = toolCall.getArguments();
        if (args == null || !args.has("path")) {
            return ToolResult.error(toolCall.getId(), "Missing 'path' argument");
        }

        try {
            Path requested = resolvePath(args.get("path").asText());
            if (!Files.exists(requested)) {
                return ToolResult.error(toolCall.getId(), "File not found: " + requested);
            }
            Path filePath = resolveExistingPath(args.get("path").asText());
            String content = Files.readString(filePath);
            ObjectNode result = mapper.createObjectNode();
            result.put("path", filePath.toString());
            result.put("content", content);
            result.put("size", Files.size(filePath));
            return ToolResult.success(toolCall.getId(), result);
        } catch (Exception e) {
            return ToolResult.error(toolCall.getId(), "Read failed: " + e.getMessage());
        }
    }

    public ToolResult writeFile(ToolCall toolCall) {
        JsonNode args = toolCall.getArguments();
        if (args == null || !args.has("path") || !args.has("content")) {
            return ToolResult.error(toolCall.getId(), "Missing 'path' or 'content' argument");
        }

        try {
            Path filePath = resolveWritablePath(args.get("path").asText());
            Files.writeString(filePath, args.get("content").asText(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
            ObjectNode result = mapper.createObjectNode();
            result.put("path", filePath.toString());
            result.put("written", true);
            return ToolResult.success(toolCall.getId(), result);
        } catch (Exception e) {
            return ToolResult.error(toolCall.getId(), "Write failed: " + e.getMessage());
        }
    }

    public ToolResult listFiles(ToolCall toolCall) {
        JsonNode args = toolCall.getArguments();
        String pathStr = (args != null && args.has("path")) ? args.get("path").asText() : ".";

        try {
            Path dirPath = resolveExistingPath(pathStr);
            if (!Files.isDirectory(dirPath)) {
                return ToolResult.error(toolCall.getId(), "Not a directory: " + dirPath);
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("path", dirPath.toString());
            var files = result.putArray("files");
            try (var stream = Files.list(dirPath)) {
                stream.forEach(p -> {
                    ObjectNode entry = files.addObject();
                    entry.put("name", p.getFileName().toString());
                    entry.put("directory", Files.isDirectory(p));
                    try {
                        entry.put("size", Files.isRegularFile(p) ? Files.size(p) : 0);
                    } catch (IOException e) {
                        entry.put("size", 0);
                    }
                });
            }
            return ToolResult.success(toolCall.getId(), result);
        } catch (Exception e) {
            return ToolResult.error(toolCall.getId(), "List failed: " + e.getMessage());
        }
    }

    private Path resolveExistingPath(String path) throws IOException {
        Path resolved = resolvePath(path).toRealPath();
        requireRealInsideSandbox(resolved, path);
        return resolved;
    }

    private Path resolveWritablePath(String path) throws IOException {
        Path resolved = resolvePath(path);
        Path ancestor = resolved.getParent();
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new SecurityException("Path has no existing sandbox ancestor: " + path);
        }
        requireRealInsideSandbox(ancestor.toRealPath(), path);
        Files.createDirectories(resolved.getParent());
        Path realParent = resolved.getParent().toRealPath();
        requireRealInsideSandbox(realParent, path);
        Path target = realParent.resolve(resolved.getFileName().toString());
        if (Files.isSymbolicLink(target)) {
            throw new SecurityException("Symbolic link writes are not allowed: " + path);
        }
        return target;
    }

    private Path resolvePath(String path) {
        Path resolved = sandboxRoot.resolve(path).normalize();
        if (!resolved.startsWith(sandboxRoot)) {
            throw new SecurityException("Path escapes sandbox: " + path);
        }
        return resolved;
    }

    private void requireRealInsideSandbox(Path resolved, String original) throws IOException {
        if (!resolved.startsWith(sandboxRoot.toRealPath())) {
            throw new SecurityException("Path escapes sandbox: " + original);
        }
    }

    public static void register(PluginManager pm, Path sandboxRoot) {
        FilePlugin plugin = new FilePlugin(sandboxRoot);
        pm.register("read_file", plugin::readFile);
        pm.register("write_file", plugin::writeFile);
        pm.register("list_files", plugin::listFiles);
    }
}
