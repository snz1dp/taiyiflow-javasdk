package io.taiyiflow.sdk.transport.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class StdioMcpServerConfig {
    private static final ObjectMapper mapper = new ObjectMapper();

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final Path cwd;

    private StdioMcpServerConfig(String command, List<String> args, Map<String, String> env, Path cwd) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Stdio MCP command is required");
        }
        this.command = command;
        this.args = args == null ? List.of() : List.copyOf(args);
        this.env = env == null ? Map.of() : Map.copyOf(env);
        this.cwd = cwd == null ? null : cwd.normalize();
    }

    public static StdioMcpServerConfig of(String command, List<String> args, Map<String, String> env) {
        return new StdioMcpServerConfig(command, args, env, null);
    }

    public static StdioMcpServerConfig of(String command, List<String> args, Map<String, String> env,
                                          Path cwd) {
        return new StdioMcpServerConfig(command, args, env, cwd);
    }

    public static StdioMcpServerConfig parse(String value) {
        return parse(value, Map.of());
    }

    public static StdioMcpServerConfig parse(String value, Map<String, String> environment) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Stdio MCP configuration is required");
        }
        if (value.startsWith("stdio://")) {
            String commandLine = value.substring("stdio://".length()).strip();
            if (commandLine.isEmpty()) {
                throw new IllegalArgumentException("stdio:// configuration has no command");
            }
            String[] tokens = commandLine.split("(?U)\\s+");
            return new StdioMcpServerConfig(tokens[0],
                    List.of(tokens).subList(1, tokens.length), environment, null);
        }
        if (!value.startsWith("data:")) {
            throw new IllegalArgumentException("Stdio MCP configuration must use stdio:// or data:");
        }
        int comma = value.indexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("Stdio MCP data URI has no payload separator");
        }
        String metadata = value.substring(5, comma);
        String payload = value.substring(comma + 1);
        try {
            byte[] json = metadata.contains("base64")
                    ? Base64.getDecoder().decode(payload.chars()
                    .filter(StdioMcpServerConfig::isBase64Character)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString())
                    : percentDecode(payload);
            return fromJson(mapper.readTree(json));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid stdio MCP data URI", exception);
        }
    }

    public static StdioMcpServerConfig fromJson(JsonNode value) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Stdio MCP configuration must be a JSON object");
        }
        if (!value.hasNonNull("command")) {
            for (String field : List.of("server_url", "url", "stdio")) {
                if (value.hasNonNull(field)) {
                    return parse(value.get(field).asText(), readEnvironment(value.get("env")));
                }
            }
            throw new IllegalArgumentException("Stdio MCP command is required");
        }
        List<String> args = new ArrayList<>();
        JsonNode argumentNodes = value.get("args");
        if (argumentNodes != null && !argumentNodes.isNull()) {
            if (!argumentNodes.isArray()) {
                throw new IllegalArgumentException("Stdio MCP args must be an array");
            }
            argumentNodes.forEach(argument -> args.add(argument.asText()));
        }
        Path cwd = value.hasNonNull("cwd") ? Path.of(value.get("cwd").asText()) : null;
        return new StdioMcpServerConfig(value.get("command").asText(), args,
                readEnvironment(value.get("env")), cwd);
    }

    public String command() {
        return command;
    }

    public List<String> args() {
        return args;
    }

    public Map<String, String> env() {
        return env;
    }

    public Path cwd() {
        return cwd;
    }

    String cacheKey() {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("command", command);
            canonical.put("args", args);
            canonical.put("env", new TreeMap<>(env));
            canonical.put("cwd", cwd == null ? null : cwd.toString());
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(canonical));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Unable to normalize stdio MCP configuration", exception);
        }
    }

    @Override
    public String toString() {
        return "StdioMcpServerConfig[args=" + args.size() + ", env=" + env.size() + "]";
    }

    private static Map<String, String> readEnvironment(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Stdio MCP env must be an object");
        }
        Map<String, String> environment = new LinkedHashMap<>();
        node.fields().forEachRemaining(field -> environment.put(field.getKey(), field.getValue().asText()));
        return environment;
    }

    private static boolean isBase64Character(int value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9' || value == '+' || value == '/' || value == '=';
    }

    private static byte[] percentDecode(String value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (codePoint == '%') {
                if (offset + 2 >= value.length()) {
                    throw new IllegalArgumentException("Invalid percent encoding in stdio MCP data URI");
                }
                int high = Character.digit(value.charAt(offset + 1), 16);
                int low = Character.digit(value.charAt(offset + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Invalid percent encoding in stdio MCP data URI");
                }
                output.write((high << 4) | low);
                offset += 3;
            } else {
                byte[] encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                output.writeBytes(encoded);
                offset += Character.charCount(codePoint);
            }
        }
        return output.toByteArray();
    }
}
