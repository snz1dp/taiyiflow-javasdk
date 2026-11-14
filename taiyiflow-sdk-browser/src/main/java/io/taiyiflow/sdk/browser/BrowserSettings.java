package io.taiyiflow.sdk.browser;

import io.taiyiflow.sdk.browser.remote.SecretProvider;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BrowserSettings {
    public enum Mode { DISABLED, HEADLESS, REMOTE_CDP }

    private final Mode mode;
    private final Path executablePath;
    private final List<String> launchArguments;
    private final Duration actionTimeout;
    private final int maxPagesPerSession;
    private final int maxReadTextCharacters;
    private final int maxConsoleMessages;
    private final int maxScreenshotBytes;
    private final boolean executeJsEnabled;
    private final Set<String> allowedHosts;
    private final Set<String> allowedCidrs;
    private final String remoteEndpoint;
    private final Map<String, String> remoteHeaders;

    private BrowserSettings(Builder builder) {
        this.mode = builder.mode;
        this.executablePath = builder.executablePath;
        this.launchArguments = List.copyOf(builder.launchArguments);
        this.actionTimeout = builder.actionTimeout;
        this.maxPagesPerSession = builder.maxPagesPerSession;
        this.maxReadTextCharacters = builder.maxReadTextCharacters;
        this.maxConsoleMessages = builder.maxConsoleMessages;
        this.maxScreenshotBytes = builder.maxScreenshotBytes;
        this.executeJsEnabled = builder.executeJsEnabled;
        this.allowedHosts = Set.copyOf(builder.allowedHosts);
        this.allowedCidrs = Set.copyOf(builder.allowedCidrs);
        this.remoteEndpoint = builder.remoteEndpoint;
        this.remoteHeaders = Map.copyOf(builder.remoteHeaders);
        if (actionTimeout.isZero() || actionTimeout.isNegative() || actionTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("Browser action timeout must be between 1ms and 2 minutes");
        }
        if (maxPagesPerSession < 1 || maxPagesPerSession > 50 || maxReadTextCharacters < 1
                || maxReadTextCharacters > 100_000 || maxConsoleMessages < 1 || maxConsoleMessages > 200
                || maxScreenshotBytes < 1024 || maxScreenshotBytes > 20 * 1024 * 1024) {
            throw new IllegalArgumentException("Browser resource limits are outside supported bounds");
        }
        if (mode == Mode.REMOTE_CDP) {
            validateRemoteEndpoint(remoteEndpoint);
        } else if (remoteEndpoint != null || !remoteHeaders.isEmpty()) {
            throw new IllegalArgumentException("Remote CDP settings require REMOTE_CDP mode");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static BrowserSettings disabled() {
        return builder().build();
    }

    public Mode getMode() { return mode; }
    public Path getExecutablePath() { return executablePath; }
    public List<String> getLaunchArguments() { return launchArguments; }
    public Duration getActionTimeout() { return actionTimeout; }
    public int getMaxPagesPerSession() { return maxPagesPerSession; }
    public int getMaxReadTextCharacters() { return maxReadTextCharacters; }
    public int getMaxConsoleMessages() { return maxConsoleMessages; }
    public int getMaxScreenshotBytes() { return maxScreenshotBytes; }
    public boolean isExecuteJsEnabled() { return executeJsEnabled; }
    public Set<String> getAllowedHosts() { return allowedHosts; }
    public Set<String> getAllowedCidrs() { return allowedCidrs; }
    String remoteEndpoint() { return remoteEndpoint; }
    Map<String, String> remoteHeaders() { return remoteHeaders; }

    public String redactedRemoteHost() {
        if (remoteEndpoint == null) return "";
        URI uri = URI.create(remoteEndpoint);
        return uri.getScheme() + "://" + (uri.getHost() == null ? "invalid" : uri.getHost())
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }

    private static void validateRemoteEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("A remote CDP endpoint is required");
        }
        URI uri = URI.create(endpoint);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("http", "https", "ws", "wss").contains(scheme) || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Remote CDP endpoint must be HTTP(S)/WS(S) without userinfo");
        }
    }

    public static final class Builder {
        private Mode mode = Mode.DISABLED;
        private Path executablePath;
        private final List<String> launchArguments = new ArrayList<>();
        private Duration actionTimeout = Duration.ofSeconds(15);
        private int maxPagesPerSession = 10;
        private int maxReadTextCharacters = 8000;
        private int maxConsoleMessages = 200;
        private int maxScreenshotBytes = 10 * 1024 * 1024;
        private boolean executeJsEnabled;
        private final Set<String> allowedHosts = new LinkedHashSet<>();
        private final Set<String> allowedCidrs = new LinkedHashSet<>();
        private String remoteEndpoint;
        private final Map<String, String> remoteHeaders = new LinkedHashMap<>();

        public Builder mode(Mode value) {
            this.mode = java.util.Objects.requireNonNull(value);
            return this;
        }

        public Builder executablePath(Path value) {
            this.executablePath = value;
            return this;
        }

        public Builder launchArguments(List<String> values) {
            this.launchArguments.clear();
            if (values != null) this.launchArguments.addAll(values);
            return this;
        }

        public Builder actionTimeout(Duration value) {
            this.actionTimeout = java.util.Objects.requireNonNull(value);
            return this;
        }

        public Builder maxPagesPerSession(int value) { this.maxPagesPerSession = value; return this; }
        public Builder maxReadTextCharacters(int value) { this.maxReadTextCharacters = value; return this; }
        public Builder maxConsoleMessages(int value) { this.maxConsoleMessages = value; return this; }
        public Builder maxScreenshotBytes(int value) { this.maxScreenshotBytes = value; return this; }
        public Builder executeJsEnabled(boolean value) { this.executeJsEnabled = value; return this; }

        public Builder allowedHosts(Set<String> values) {
            this.allowedHosts.clear();
            if (values != null) values.forEach(value -> this.allowedHosts.add(value.toLowerCase(java.util.Locale.ROOT)));
            return this;
        }

        public Builder allowedCidrs(Set<String> values) {
            this.allowedCidrs.clear();
            if (values != null) this.allowedCidrs.addAll(values);
            return this;
        }

        public Builder remoteEndpoint(String value) {
            this.remoteEndpoint = value;
            return this;
        }

        public Builder remoteEndpointSecret(String name, SecretProvider provider) {
            this.remoteEndpoint = provider.resolve(name);
            return this;
        }

        public Builder remoteHeader(String name, String value) {
            if (name == null || name.isBlank() || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                    || value == null || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Invalid remote header name or value");
            }
            this.remoteHeaders.put(name, value);
            return this;
        }

        public Builder remoteHeaderSecret(String header, String name, SecretProvider provider) {
            return remoteHeader(header, provider.resolve(name));
        }

        public BrowserSettings build() {
            return new BrowserSettings(this);
        }
    }
}
