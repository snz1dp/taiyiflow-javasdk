package io.taiyiflow.sdk.core.instructions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the {@code client_instructions} package from explicitly configured
 * directories only. No managed/user/home discovery is performed; supply is
 * opt-in via {@code TaiyiAgent.Builder.instructionDirectories}.
 *
 * <p>Wire shape mirrors the Electron client: {@code {layers, merged}} where the
 * backend consumes {@code merged} directly. File paths are disclosed relative
 * to their configured directory, never as absolute local paths.</p>
 */
public final class ClientInstructionsLoader {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ClientInstructionsLoader.class);

    public static final long MAX_FILE_SIZE = 256L * 1024;
    public static final long MAX_TOTAL_SIZE = 1024L * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> KNOWN_FILES =
            List.of("CLAUDE.md", "AGENTS.md", ".github/copilot-instructions.md");
    private static final int PROJECT_PRIORITY = 20;
    private static final int LOCAL_PRIORITY = 25;

    private ClientInstructionsLoader() {
    }

    /** Returns null when no instruction content is available. */
    public static JsonNode load(List<Path> directories) {
        if (directories == null || directories.isEmpty()) {
            return null;
        }
        List<Layer> layers = new ArrayList<>();
        int position = 0;
        for (Path directory : directories) {
            Path root = directory.toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                continue;
            }
            for (String knownFile : KNOWN_FILES) {
                position = discover(root, knownFile, "project", PROJECT_PRIORITY, position, layers);
                position = discover(root, localVariant(knownFile), "local", LOCAL_PRIORITY, position, layers);
            }
        }
        layers.sort(Comparator.comparingInt(Layer::priority).thenComparingInt(Layer::position));

        ArrayNode serializable = MAPPER.createArrayNode();
        List<String> parts = new ArrayList<>();
        long totalSize = 0;
        for (Layer layer : layers) {
            if (layer.content.isEmpty()) {
                continue;
            }
            // Mirrors Electron toPackage exactly: budget counts UTF-16 content length (not bytes
            // nor merged headers), and exceeding it drops the remaining layers rather than slicing.
            if (totalSize + layer.content.length() > MAX_TOTAL_SIZE) {
                log.warn("client_instructions total size limit ({}) reached, truncating at {}",
                        MAX_TOTAL_SIZE, layer.filePath);
                break;
            }
            ObjectNode node = serializable.addObject();
            node.put("scope", layer.scope);
            node.put("filePath", layer.filePath);
            node.put("content", layer.content);
            node.put("priority", layer.priority);
            parts.add("[Instructions from " + layer.filePath + " (" + layer.scope
                    + ", priority " + layer.priority + ")]\n" + layer.content);
            totalSize += layer.content.length();
        }
        if (serializable.isEmpty()) {
            return null;
        }
        ObjectNode pkg = MAPPER.createObjectNode();
        pkg.set("layers", serializable);
        pkg.put("merged", String.join("\n\n", parts));
        return pkg;
    }

    private static int discover(Path root, String relativeFile, String scope, int priority,
                                int position, List<Layer> layers) {
        Path file = root.resolve(relativeFile).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return position;
        }
        try {
            if (Files.size(file) > MAX_FILE_SIZE) {
                return position;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return position;
            }
            layers.add(new Layer(scope, relativeFile, content, priority, position));
            return position + 1;
        } catch (IOException exception) {
            return position;
        }
    }

    private static String localVariant(String knownFile) {
        int dot = knownFile.lastIndexOf('.');
        return knownFile.substring(0, dot) + ".local" + knownFile.substring(dot);
    }

    private record Layer(String scope, String filePath, String content, int priority, int position) {
    }
}