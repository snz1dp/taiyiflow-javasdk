package io.taiyiflow.sdk.plugins.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Text and regex search plugin.
 */
public class SearchPlugin {

    private static final Logger log = LoggerFactory.getLogger(SearchPlugin.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RESULTS = 100;

    private final Path sandboxRoot;

    public SearchPlugin(Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot;
    }

    public ToolResult search(ToolCall toolCall) {
        JsonNode args = toolCall.getArguments();
        if (args == null || !args.has("pattern")) {
            return ToolResult.error(toolCall.getId(), "Missing 'pattern' argument");
        }

        String pattern = args.get("pattern").asText();
        boolean isRegex = args.has("regex") && args.get("regex").asBoolean();
        String pathStr = args.has("path") ? args.get("path").asText() : ".";

        try {
            Path searchRoot = sandboxRoot.resolve(pathStr).normalize();
            if (!searchRoot.startsWith(sandboxRoot)) {
                return ToolResult.error(toolCall.getId(), "Path escapes sandbox");
            }

            Pattern compiled = isRegex ? Pattern.compile(pattern) : Pattern.compile(Pattern.quote(pattern));
            ObjectNode result = mapper.createObjectNode();
            var matches = result.putArray("matches");
            int[] count = {0};

            try (Stream<Path> paths = Files.walk(searchRoot)) {
                paths.filter(Files::isRegularFile).forEach(file -> {
                    if (count[0] >= MAX_RESULTS) return;
                    try {
                        String content = Files.readString(file);
                        String[] lines = content.split("\n");
                        for (int i = 0; i < lines.length && count[0] < MAX_RESULTS; i++) {
                            if (compiled.matcher(lines[i]).find()) {
                                ObjectNode match = matches.addObject();
                                match.put("file", sandboxRoot.relativize(file).toString());
                                match.put("line", i + 1);
                                match.put("content", lines[i].strip());
                                count[0]++;
                            }
                        }
                    } catch (Exception e) {
                        // Skip unreadable files
                    }
                });
            }

            result.put("totalMatches", count[0]);
            result.put("truncated", count[0] >= MAX_RESULTS);
            return ToolResult.success(toolCall.getId(), result);
        } catch (Exception e) {
            return ToolResult.error(toolCall.getId(), "Search failed: " + e.getMessage());
        }
    }

    public static void register(PluginManager pm, Path sandboxRoot) {
        SearchPlugin plugin = new SearchPlugin(sandboxRoot);
        pm.register("search", plugin::search);
        pm.register("grep", plugin::search);
    }
}
