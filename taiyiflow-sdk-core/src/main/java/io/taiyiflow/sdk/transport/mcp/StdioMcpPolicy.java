package io.taiyiflow.sdk.transport.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StdioMcpPolicy {
    private static final Set<String> SAFE_INHERITED_ENV = Set.of(
            "HOME", "LOGNAME", "PATH", "SHELL", "TERM", "USER");

    private final Set<String> allowedCommands;
    private final Path workspace;
    private final Set<String> allowedEnvironment;
    private final Set<String> inheritedEnvironment;
    private final int maxServers;
    private final int maxArguments;
    private final int maxArgumentChars;
    private final int maxFrameChars;
    private final int outputTailChars;
    private final Duration maxLifetime;
    private final Duration stopTimeout;

    private StdioMcpPolicy(Builder builder) {
        this.allowedCommands = Set.copyOf(builder.allowedCommands);
        this.workspace = builder.workspace == null ? Path.of("").toAbsolutePath().normalize()
                : builder.workspace.toAbsolutePath().normalize();
        this.allowedEnvironment = Set.copyOf(builder.allowedEnvironment);
        this.inheritedEnvironment = Set.copyOf(builder.inheritedEnvironment);
        this.maxServers = builder.maxServers;
        this.maxArguments = builder.maxArguments;
        this.maxArgumentChars = builder.maxArgumentChars;
        this.maxFrameChars = builder.maxFrameChars;
        this.outputTailChars = builder.outputTailChars;
        this.maxLifetime = builder.maxLifetime;
        this.stopTimeout = builder.stopTimeout;
    }

    public static Builder builder(Path workspace) {
        return new Builder(workspace);
    }

    public void validate(StdioMcpServerConfig config) {
        if (!allowedCommands.contains(config.command())) {
            throw new IllegalArgumentException("Stdio MCP command is not allowed");
        }
        if (config.args().size() > maxArguments
                || config.args().stream().anyMatch(value -> value.length() > maxArgumentChars)) {
            throw new IllegalArgumentException("Stdio MCP argument limits exceeded");
        }
        for (String name : config.env().keySet()) {
            if (!allowedEnvironment.contains(name)) {
                throw new IllegalArgumentException("Stdio MCP environment variable is not allowed: " + name);
            }
        }
        Path cwd = workingDirectory(config);
        try {
            Path realWorkspace = workspace.toRealPath();
            Path realCwd = cwd.toRealPath();
            if (!realCwd.startsWith(realWorkspace) || !Files.isDirectory(realCwd)) {
                throw new IllegalArgumentException("Stdio MCP cwd escapes its workspace");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Stdio MCP cwd is not accessible", exception);
        }
    }

    public Path workingDirectory(StdioMcpServerConfig config) {
        Path cwd = config.cwd() == null ? workspace : config.cwd();
        return (cwd.isAbsolute() ? cwd : workspace.resolve(cwd)).normalize();
    }

    public Map<String, String> environment(StdioMcpServerConfig config) {
        Map<String, String> environment = new java.util.LinkedHashMap<>();
        for (String name : inheritedEnvironment) {
            String value = System.getenv(name);
            if (value != null) {
                environment.put(name, value);
            }
        }
        config.env().forEach((name, value) -> {
            if (allowedEnvironment.contains(name)) {
                environment.put(name, value);
            }
        });
        return environment;
    }

    public int maxServers() {
        return maxServers;
    }

    public int maxFrameChars() {
        return maxFrameChars;
    }

    public int outputTailChars() {
        return outputTailChars;
    }

    public Duration maxLifetime() {
        return maxLifetime;
    }

    public Duration stopTimeout() {
        return stopTimeout;
    }

    public static final class Builder {
        private final Path workspace;
        private final Set<String> allowedCommands = new LinkedHashSet<>();
        private final Set<String> allowedEnvironment = new LinkedHashSet<>();
        private Set<String> inheritedEnvironment = SAFE_INHERITED_ENV;
        private int maxServers = 16;
        private int maxArguments = 64;
        private int maxArgumentChars = 16_384;
        private int maxFrameChars = 1_048_576;
        private int outputTailChars = 65_536;
        private Duration maxLifetime = Duration.ofMinutes(30);
        private Duration stopTimeout = Duration.ofSeconds(2);

        private Builder(Path workspace) {
            this.workspace = workspace;
        }

        public Builder allowCommand(String command) {
            allowedCommands.add(command);
            return this;
        }

        public Builder allowEnvironment(String... names) {
            allowedEnvironment.addAll(List.of(names));
            return this;
        }

        public Builder inheritedEnvironment(Set<String> names) {
            this.inheritedEnvironment = Set.copyOf(names);
            return this;
        }

        public Builder maxServers(int value) {
            this.maxServers = positive(value, "maxServers");
            return this;
        }

        public Builder maxArguments(int value) {
            this.maxArguments = positive(value, "maxArguments");
            return this;
        }

        public Builder maxArgumentChars(int value) {
            this.maxArgumentChars = positive(value, "maxArgumentChars");
            return this;
        }

        public Builder maxFrameChars(int value) {
            this.maxFrameChars = positive(value, "maxFrameChars");
            return this;
        }

        public Builder outputTailChars(int value) {
            this.outputTailChars = positive(value, "outputTailChars");
            return this;
        }

        public Builder maxLifetime(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("maxLifetime must be positive");
            }
            this.maxLifetime = value;
            return this;
        }

        public Builder stopTimeout(Duration value) {
            if (value == null || value.isNegative()) {
                throw new IllegalArgumentException("stopTimeout must not be negative");
            }
            this.stopTimeout = value;
            return this;
        }

        public StdioMcpPolicy build() {
            if (workspace == null) {
                throw new IllegalArgumentException("workspace is required");
            }
            return new StdioMcpPolicy(this);
        }

        private static int positive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }
    }
}
