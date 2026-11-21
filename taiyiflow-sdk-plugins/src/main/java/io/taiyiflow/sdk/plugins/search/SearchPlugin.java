package io.taiyiflow.sdk.plugins.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.taiyiflow.sdk.plugins.ToolDefinitions.tool;

/**
 * Text and regex search plugin.
 */
public class SearchPlugin {

    private static final Logger log = LoggerFactory.getLogger(SearchPlugin.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_RESULTS = 100;
    private static final int SEARCH_FILES_MAX_RESULTS = 200;

    // Mirrors Electron file-tools.js SKIP_DIRS / BINARY_EXTENSIONS so search_files
    // skips the same heavy directories and binary files under the same Flow.
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".hg",
            "node_modules", "vendor", "bower_components", ".pnpm-store",
            "venv", ".venv", "env", "__pypackages__", ".bundle", "Pods",
            "dist", "build", "out", "target", "bin", "obj",
            ".next", ".nuxt", ".output", ".turbo", ".quasar",
            ".idea", ".vscode", ".vs", ".eclipse", ".settings",
            ".cache", "__pycache__", ".parcel-cache", ".pytest_cache",
            ".mypy_cache", ".ruff_cache", ".tox", ".nox",
            "tmp", "temp", ".tmp",
            "coverage", ".nyc_output", "htmlcov",
            ".docker", ".terraform", ".serverless",
            ".DS_Store", "Thumbs.db", "desktop.ini",
            ".gradle", ".m2",
            ".cocoapods",
            ".dart_tool", ".pub-cache",
            ".angular", ".svelte-kit", ".astro",
            ".snz1dp",
            ".eggs",
            ".gotestcache",
            ".tap");

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".webp", ".avif", ".tiff", ".tif",
            ".mp3", ".mp4", ".wav", ".ogg", ".flac", ".aac",
            ".avi", ".mov", ".wmv", ".flv", ".mkv", ".webm",
            ".zip", ".tar", ".gz", ".bz2", ".xz", ".7z", ".rar", ".zst",
            ".exe", ".dll", ".so", ".dylib", ".a", ".o", ".obj", ".lib",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".woff", ".woff2", ".ttf", ".eot", ".otf",
            ".class", ".pyc", ".pyo", ".jar", ".war", ".ear",
            ".sqlite", ".db", ".mdb",
            ".iso", ".img", ".dmg",
            ".bin", ".dat", ".lock",
            ".svg", ".wasm");

    private final Path sandboxRoot;

    public SearchPlugin(Path sandboxRoot) {
        this.sandboxRoot = sandboxRoot.toAbsolutePath().normalize();
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
            Path sandboxRealRoot = sandboxRoot.toRealPath();
            Path requestedRoot = sandboxRoot.resolve(pathStr).normalize();
            if (!requestedRoot.startsWith(sandboxRoot)) {
                return ToolResult.error(toolCall.getId(), "Path escapes sandbox");
            }
            Path searchRoot = requestedRoot.toRealPath();
            if (!searchRoot.startsWith(sandboxRealRoot)) {
                return ToolResult.error(toolCall.getId(), "Path escapes sandbox");
            }

            Pattern compiled = isRegex ? Pattern.compile(pattern) : Pattern.compile(Pattern.quote(pattern));
            ObjectNode result = mapper.createObjectNode();
            var matches = result.putArray("matches");
            int[] count = {0};

            try (Stream<Path> paths = Files.walk(searchRoot)) {
                paths.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).forEach(file -> {
                    if (count[0] >= MAX_RESULTS) return;
                    try {
                        String content = Files.readString(file);
                        String[] lines = content.split("\n");
                        for (int i = 0; i < lines.length && count[0] < MAX_RESULTS; i++) {
                            if (compiled.matcher(lines[i]).find()) {
                                ObjectNode match = matches.addObject();
                                match.put("file", sandboxRealRoot.relativize(file).toString());
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

    public ToolResult glob(ToolCall toolCall) {
        JsonNode args = toolCall.getArguments();
        String pattern = args == null ? "**/*" : args.path("pattern").asText("**/*");
        String path = args == null ? "." : args.path("path").asText(".");
        if (pattern.isBlank() || pattern.length() > 1000) {
            return ToolResult.error(toolCall.getId(), "A bounded glob pattern is required");
        }
        try {
            Path sandboxRealRoot = sandboxRoot.toRealPath();
            Path requestedRoot = sandboxRoot.resolve(path).normalize();
            if (!requestedRoot.startsWith(sandboxRoot)) return ToolResult.error(toolCall.getId(), "Path escapes sandbox");
            Path searchRoot = requestedRoot.toRealPath();
            if (!searchRoot.startsWith(sandboxRealRoot)) return ToolResult.error(toolCall.getId(), "Path escapes sandbox");
            var matcher = searchRoot.getFileSystem().getPathMatcher("glob:" + pattern);
            List<Path> files;
            try (Stream<Path> paths = Files.walk(searchRoot)) {
                files = paths.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        .filter(file -> matcher.matches(searchRoot.relativize(file))).sorted()
                        .limit(1001).toList();
            }
            ObjectNode result = mapper.createObjectNode();
            ArrayNode matches = result.putArray("files");
            files.stream().limit(1000).forEach(file -> matches.add(sandboxRealRoot.relativize(file).toString()));
            result.put("count", Math.min(files.size(), 1000));
            result.put("truncated", files.size() > 1000);
            return ToolResult.success(toolCall.getId(), result);
        } catch (Exception exception) {
            return ToolResult.error(toolCall.getId(), "Glob failed: " + exception.getMessage());
        }
    }

    public static void register(PluginManager pm, Path sandboxRoot) {
        SearchPlugin plugin = new SearchPlugin(sandboxRoot);
        String schema = "{\"pattern\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"},"
                + "\"regex\":{\"type\":\"boolean\"}}";
        pm.register(tool("search", "Search text in workspace files", schema, "pattern"), plugin::search);
        pm.register(tool("grep", "Search text in workspace files", schema, "pattern"), plugin::search);
        pm.register(tool("glob_files", "List sandbox files matching a glob pattern",
                "{\"pattern\":{\"type\":\"string\"},\"path\":{\"type\":\"string\"}}"), plugin::glob);
        pm.register(tool("search_files",
                "Search text in workspace files with regex, glob filters, include/exclude patterns and pagination",
                "{\"pattern\":{\"type\":\"string\",\"description\":\"Text or regular expression to search for.\"},"
                        + "\"path\":{\"type\":\"string\",\"description\":\"Search root, defaults to the workspace root.\"},"
                        + "\"glob\":{\"type\":\"string\",\"description\":\"File glob filter, e.g. **/*.js.\"},"
                        + "\"includePatterns\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
                        + "\"description\":\"Only files matching one of these glob patterns are searched.\"},"
                        + "\"excludePatterns\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
                        + "\"description\":\"Files matching one of these glob patterns are skipped.\"},"
                        + "\"caseSensitive\":{\"type\":\"boolean\",\"description\":\"Defaults to true.\"},"
                        + "\"isRegexp\":{\"type\":\"boolean\",\"description\":\"Treat pattern as a regular expression."
                        + " Defaults to false (literal text); set true when pattern contains regex syntax such as | () [].\"},"
                        + "\"includeHidden\":{\"type\":\"boolean\",\"description\":\"Include hidden files/directories, defaults to false.\"},"
                        + "\"outputMode\":{\"type\":\"string\",\"enum\":[\"content\",\"files_with_matches\",\"count\"],"
                        + "\"description\":\"Defaults to content.\"},"
                        + "\"offset\":{\"type\":\"integer\",\"minimum\":0,"
                        + "\"description\":\"Skip the first N matches (content mode only).\"},"
                        + "\"maxResults\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":200,"
                        + "\"description\":\"Defaults to 200, capped at 200.\"}}",
                "pattern"), plugin::searchFiles);
    }

    /**
     * Electron search_files contract: content/files_with_matches/count output modes,
     * offset pagination and has_more/total_matches keys. Fields are sandbox-relative:
     * path is relative to the sandbox real root, relative_path to the search root, and
     * root is a relative label ("." for the sandbox root). This mirrors the ripgrep
     * engine's relative output rather than the node fallback's absolute fullPath, so no
     * host absolute path is disclosed. The engine field keeps the honest "java" value —
     * this engine does not impersonate rg/node.
     */
    public ToolResult searchFiles(ToolCall toolCall) {
        JsonNode args = toolCall.getArguments();
        if (args == null || !args.has("pattern")) {
            return ToolResult.error(toolCall.getId(), "Missing 'pattern' argument");
        }
        String pattern = args.get("pattern").asText();
        boolean caseSensitive = args.path("caseSensitive").asBoolean(true);
        boolean isRegexp = args.path("isRegexp").asBoolean(false);
        boolean includeHidden = args.path("includeHidden").asBoolean(false);
        String outputMode = args.path("outputMode").asText("content");
        if (!outputMode.equals("files_with_matches") && !outputMode.equals("count")) {
            outputMode = "content";
        }
        int maxResults = args.path("maxResults").asInt(SEARCH_FILES_MAX_RESULTS);
        if (maxResults <= 0) {
            maxResults = SEARCH_FILES_MAX_RESULTS;
        }
        maxResults = Math.min(maxResults, SEARCH_FILES_MAX_RESULTS);
        int offset = Math.max(0, args.path("offset").asInt(0));
        String pathStr = args.path("path").asText(".");

        try {
            Path sandboxRealRoot = sandboxRoot.toRealPath();
            Path requestedRoot = sandboxRoot.resolve(pathStr).normalize();
            if (!requestedRoot.startsWith(sandboxRoot)) {
                return ToolResult.error(toolCall.getId(), "Path escapes sandbox");
            }
            Path searchRoot = requestedRoot.toRealPath();
            if (!searchRoot.startsWith(sandboxRealRoot)) {
                return ToolResult.error(toolCall.getId(), "Path escapes sandbox");
            }

            // UNICODE_CASE pairs with CASE_INSENSITIVE so non-ASCII letters fold too (Ä/ä),
            // matching Electron's rg -i / JS 'gi' behavior.
            Pattern compiled = Pattern.compile(isRegexp ? pattern : Pattern.quote(pattern),
                    caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Pattern glob = optionalGlob(args.path("glob").asText(null));
            List<Pattern> includes = globs(args.get("includePatterns"));
            List<Pattern> excludes = globs(args.get("excludePatterns"));

            List<Path> candidates = new ArrayList<>();
            if (Files.isRegularFile(searchRoot, LinkOption.NOFOLLOW_LINKS)) {
                if (!hasBinaryExtension(searchRoot)) {
                    candidates.add(searchRoot);
                }
            } else {
                Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(searchRoot)) {
                            String name = dir.getFileName().toString();
                            if (SKIP_DIRS.contains(name) || (!includeHidden && name.startsWith("."))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()
                                && (includeHidden || !file.getFileName().toString().startsWith("."))
                                && !hasBinaryExtension(file)
                                && matchesFilters(relativize(searchRoot, file), glob, includes, excludes)) {
                            candidates.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            candidates.sort(Path::compareTo);

            List<ObjectNode> contentMatches = new ArrayList<>();
            Map<String, Integer> fileCounts = new LinkedHashMap<>();
            int totalMatches = 0;
            for (Path file : candidates) {
                if (windowFull(outputMode, contentMatches.size(), fileCounts.size(), maxResults)) {
                    break;
                }
                if (startsWithNulByte(file)) {
                    continue;
                }
                String content;
                try {
                    content = Files.readString(file);
                } catch (Exception exception) {
                    continue;
                }
                String relativePath = relativize(searchRoot, file);
                String[] lines = content.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    if (windowFull(outputMode, contentMatches.size(), fileCounts.size(), maxResults)) {
                        break;
                    }
                    if (compiled.matcher(lines[i]).find()) {
                        totalMatches++;
                        fileCounts.merge(relativePath, 1, Integer::sum);
                        if (outputMode.equals("content") && totalMatches > offset
                                && contentMatches.size() < maxResults) {
                            ObjectNode match = mapper.createObjectNode();
                            match.put("path", sandboxRealRoot.relativize(file).toString());
                            match.put("relative_path", relativePath);
                            match.put("line", i + 1);
                            match.put("text", lines[i].stripTrailing());
                            contentMatches.add(match);
                        }
                    }
                }
            }

            String rootLabel = searchRoot.equals(sandboxRealRoot)
                    ? "." : sandboxRealRoot.relativize(searchRoot).toString();
            ObjectNode result = mapper.createObjectNode();
            result.put("root", rootLabel);
            result.put("engine", "java");
            result.put("output_mode", outputMode);
            result.put("total_matches", totalMatches);
            switch (outputMode) {
                case "files_with_matches" -> {
                    ArrayNode files = result.putArray("files");
                    fileCounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                        ObjectNode file = files.addObject();
                        file.put("relative_path", entry.getKey());
                        file.put("count", entry.getValue());
                    });
                    result.put("count", fileCounts.size());
                }
                case "count" -> {
                    result.putArray("matches");
                    result.put("count", totalMatches);
                }
                default -> {
                    ArrayNode matches = result.putArray("matches");
                    contentMatches.forEach(matches::add);
                    result.put("count", contentMatches.size());
                    result.put("offset", offset);
                    result.put("has_more", contentMatches.size() >= maxResults);
                }
            }
            return ToolResult.success(toolCall.getId(), result);
        } catch (Exception exception) {
            return ToolResult.error(toolCall.getId(), "Search files failed: " + exception.getMessage());
        }
    }

    private static boolean windowFull(String outputMode, int contentMatches, int fileCount, int maxResults) {
        return switch (outputMode) {
            case "content" -> contentMatches >= maxResults;
            case "files_with_matches" -> fileCount >= maxResults;
            default -> false;
        };
    }

    private static String relativize(Path searchRoot, Path file) {
        if (file.equals(searchRoot)) {
            return file.getFileName().toString();
        }
        return searchRoot.relativize(file).toString().replace('\\', '/');
    }

    private static boolean matchesFilters(String relativePath, Pattern glob,
                                          List<Pattern> includes, List<Pattern> excludes) {
        if (glob != null && !glob.matcher(relativePath).matches()) {
            return false;
        }
        if (!includes.isEmpty() && includes.stream().noneMatch(pattern -> pattern.matcher(relativePath).matches())) {
            return false;
        }
        return excludes.stream().noneMatch(pattern -> pattern.matcher(relativePath).matches());
    }

    private static Pattern optionalGlob(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        return compileGlob(pattern);
    }

    private static List<Pattern> globs(JsonNode patterns) {
        if (patterns == null || !patterns.isArray()) {
            return List.of();
        }
        List<Pattern> result = new ArrayList<>();
        for (JsonNode item : patterns) {
            if (item.isTextual() && !item.asText().isBlank()) {
                result.add(compileGlob(item.asText()));
            }
        }
        return result;
    }

    private static Pattern compileGlob(String glob) {
        return Pattern.compile("^" + globBody(glob) + "$");
    }

    /**
     * Electron globToRegex semantics: a double-star followed by a slash matches zero or
     * more directories, a single star stays within one path segment, braces expand to
     * alternations and character classes ([0-9], [!a]) pass through to the regex engine.
     * Deliberate deviation: Electron leaves +()|^$ unescaped (accidental regex syntax);
     * this translator keeps escaping them so those characters stay literal in globs.
     */
    private static String globBody(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                        regex.append("(?:.*/)?");
                        i += 3;
                    } else {
                        regex.append(".*");
                        i += 2;
                    }
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else if (c == '{') {
                int close = matchingBrace(glob, i);
                if (close < 0) {
                    regex.append("\\{");
                    i++;
                } else {
                    List<String> options = splitTopLevelCommas(glob.substring(i + 1, close));
                    regex.append("(?:");
                    for (int k = 0; k < options.size(); k++) {
                        if (k > 0) {
                            regex.append('|');
                        }
                        regex.append(globBody(options.get(k)));
                    }
                    regex.append(')');
                    i = close + 1;
                }
            } else if (c == '[') {
                // Electron globToRegex passes character classes through to the regex engine
                // (it only escapes '.' outside braces), so [0-9] ranges must keep working.
                // Glob negation [!a] is translated to regex [^a]; an unclosed '[' stays literal.
                int close = closingBracket(glob, i);
                if (close < 0) {
                    regex.append("\\[");
                    i++;
                } else {
                    String inner = glob.substring(i + 1, close);
                    regex.append('[');
                    if (inner.startsWith("!")) {
                        regex.append('^').append(inner, 1, inner.length());
                    } else {
                        regex.append(inner);
                    }
                    regex.append(']');
                    i = close + 1;
                }
            } else {
                if (".\\+()^$|]".indexOf(c) >= 0) {
                    regex.append('\\');
                }
                regex.append(c);
                i++;
            }
        }
        return regex.toString();
    }

    /** Find the ']' closing a character class; a leading ']' (after optional !/^) is a member. */
    private static int closingBracket(String glob, int open) {
        int i = open + 1;
        if (i < glob.length() && (glob.charAt(i) == '!' || glob.charAt(i) == '^')) {
            i++;
        }
        if (i < glob.length() && glob.charAt(i) == ']') {
            i++;
        }
        for (; i < glob.length(); i++) {
            if (glob.charAt(i) == ']') {
                return i;
            }
        }
        return -1;
    }

    private static int matchingBrace(String glob, int open) {
        int depth = 0;
        for (int i = open; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> splitTopLevelCommas(String inner) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(inner.substring(start));
        return parts;
    }

    private static boolean hasBinaryExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && BINARY_EXTENSIONS.contains(name.substring(dot));
    }

    private static boolean startsWithNulByte(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            for (byte b : input.readNBytes(512)) {
                if (b == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException exception) {
            return true;
        }
    }
}
