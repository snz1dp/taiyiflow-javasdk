package io.taiyiflow.sdk.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuration properties for TaiyiFlow SDK.
 */
@ConfigurationProperties(prefix = "taiyiflow")
public class TaiyiFlowProperties {

    /**
     * Base URL of the TaiyiFlow platform.
     */
    private String baseUrl;

    /**
     * API key for authentication.
     */
    private String apiKey;

    /**
     * Flow ID or name to bind to.
     */
    private String flowId;

    /**
     * Connection timeout.
     */
    private Duration connectTimeout = Duration.ofSeconds(10);

    /**
     * Request timeout.
     */
    private Duration requestTimeout = Duration.ofSeconds(30);

    /**
     * Maximum concurrent sessions.
     */
    private int maxSessions = 100;

    /**
     * Enable collaboration plugin.
     */
    private boolean collaborationEnabled = false;

    /**
     * Sandbox root directory for file/shell plugins.
     */
    private String sandboxRoot = "/tmp/taiyiflow-sandbox";

    /**
     * Allowed shell commands (whitelist).
     */
    private List<String> allowedShellCommands = List.of("ls", "cat", "echo", "date", "whoami", "pwd");

    /**
     * Enable built-in plugins.
     */
    private Plugins plugins = new Plugins();

    public static class Plugins {
        private boolean shell = true;
        private boolean files = true;
        private boolean search = true;

        public boolean isShell() { return shell; }
        public void setShell(boolean shell) { this.shell = shell; }
        public boolean isFiles() { return files; }
        public void setFiles(boolean files) { this.files = files; }
        public boolean isSearch() { return search; }
        public void setSearch(boolean search) { this.search = search; }
    }

    // Getters and Setters
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getFlowId() { return flowId; }
    public void setFlowId(String flowId) { this.flowId = flowId; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getMaxSessions() { return maxSessions; }
    public void setMaxSessions(int maxSessions) { this.maxSessions = maxSessions; }
    public boolean isCollaborationEnabled() { return collaborationEnabled; }
    public void setCollaborationEnabled(boolean collaborationEnabled) { this.collaborationEnabled = collaborationEnabled; }
    public String getSandboxRoot() { return sandboxRoot; }
    public void setSandboxRoot(String sandboxRoot) { this.sandboxRoot = sandboxRoot; }
    public List<String> getAllowedShellCommands() { return allowedShellCommands; }
    public void setAllowedShellCommands(List<String> allowedShellCommands) { this.allowedShellCommands = allowedShellCommands; }
    public Plugins getPlugins() { return plugins; }
    public void setPlugins(Plugins plugins) { this.plugins = plugins; }
}
