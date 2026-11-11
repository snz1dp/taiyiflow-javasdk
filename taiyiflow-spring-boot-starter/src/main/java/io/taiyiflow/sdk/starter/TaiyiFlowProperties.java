package io.taiyiflow.sdk.starter;

import io.taiyiflow.sdk.RecoveryPolicy;
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

    private int eventQueueCapacity = 1024;

    private boolean sseFallbackEnabled = true;

    private RecoveryPolicy recoveryPolicy = RecoveryPolicy.MANUAL;

    private Duration recoveryMinBackoff = Duration.ofSeconds(2);

    private Duration recoveryMaxBackoff = Duration.ofSeconds(60);

    private int recoveryMaxAttempts = 8;

    private boolean autoRestartOnExecutionLoss = true;

    private Duration permissionTimeout = Duration.ofSeconds(300);

    /**
     * Enable collaboration plugin.
     */
    private boolean collaborationEnabled = false;

    /**
     * Sandbox root for file/search plugins and working directory for the shell plugin.
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
    public int getEventQueueCapacity() { return eventQueueCapacity; }
    public void setEventQueueCapacity(int eventQueueCapacity) { this.eventQueueCapacity = eventQueueCapacity; }
    public boolean isSseFallbackEnabled() { return sseFallbackEnabled; }
    public void setSseFallbackEnabled(boolean sseFallbackEnabled) { this.sseFallbackEnabled = sseFallbackEnabled; }
    public RecoveryPolicy getRecoveryPolicy() { return recoveryPolicy; }
    public void setRecoveryPolicy(RecoveryPolicy recoveryPolicy) { this.recoveryPolicy = recoveryPolicy; }
    public Duration getRecoveryMinBackoff() { return recoveryMinBackoff; }
    public void setRecoveryMinBackoff(Duration recoveryMinBackoff) { this.recoveryMinBackoff = recoveryMinBackoff; }
    public Duration getRecoveryMaxBackoff() { return recoveryMaxBackoff; }
    public void setRecoveryMaxBackoff(Duration recoveryMaxBackoff) { this.recoveryMaxBackoff = recoveryMaxBackoff; }
    public int getRecoveryMaxAttempts() { return recoveryMaxAttempts; }
    public void setRecoveryMaxAttempts(int recoveryMaxAttempts) { this.recoveryMaxAttempts = recoveryMaxAttempts; }
    public boolean isAutoRestartOnExecutionLoss() { return autoRestartOnExecutionLoss; }
    public void setAutoRestartOnExecutionLoss(boolean autoRestartOnExecutionLoss) { this.autoRestartOnExecutionLoss = autoRestartOnExecutionLoss; }
    public Duration getPermissionTimeout() { return permissionTimeout; }
    public void setPermissionTimeout(Duration permissionTimeout) { this.permissionTimeout = permissionTimeout; }
    public boolean isCollaborationEnabled() { return collaborationEnabled; }
    public void setCollaborationEnabled(boolean collaborationEnabled) { this.collaborationEnabled = collaborationEnabled; }
    public String getSandboxRoot() { return sandboxRoot; }
    public void setSandboxRoot(String sandboxRoot) { this.sandboxRoot = sandboxRoot; }
    public List<String> getAllowedShellCommands() { return allowedShellCommands; }
    public void setAllowedShellCommands(List<String> allowedShellCommands) { this.allowedShellCommands = allowedShellCommands; }
    public Plugins getPlugins() { return plugins; }
    public void setPlugins(Plugins plugins) { this.plugins = plugins; }
}
