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

    private boolean useDefaultFlow;

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
    private Skills skills = new Skills();
    private Browser browser = new Browser();

    public static class Plugins {
        private boolean shell = true;
        private boolean files = true;
        private boolean search = true;
        private boolean media = false;

        public boolean isShell() { return shell; }
        public void setShell(boolean shell) { this.shell = shell; }
        public boolean isFiles() { return files; }
        public void setFiles(boolean files) { this.files = files; }
        public boolean isSearch() { return search; }
        public void setSearch(boolean search) { this.search = search; }
        public boolean isMedia() { return media; }
        public void setMedia(boolean media) { this.media = media; }
    }

    public static class Skills {
        private boolean enabled = false;
        private boolean platformEnabled = false;
        private String workspaceDir;
        private String userDir;
        private String compatDir;
        private List<String> defaultNames = List.of();
        private List<String> bundledResources = List.of();
        private boolean toleratePlatformFailure = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public boolean isPlatformEnabled() { return platformEnabled; }
        public void setPlatformEnabled(boolean value) { platformEnabled = value; }
        public String getWorkspaceDir() { return workspaceDir; }
        public void setWorkspaceDir(String value) { workspaceDir = value; }
        public String getUserDir() { return userDir; }
        public void setUserDir(String value) { userDir = value; }
        public String getCompatDir() { return compatDir; }
        public void setCompatDir(String value) { compatDir = value; }
        public List<String> getDefaultNames() { return defaultNames; }
        public void setDefaultNames(List<String> value) { defaultNames = value; }
        public List<String> getBundledResources() { return bundledResources; }
        public void setBundledResources(List<String> value) { bundledResources = value; }
        public boolean isToleratePlatformFailure() { return toleratePlatformFailure; }
        public void setToleratePlatformFailure(boolean value) { toleratePlatformFailure = value; }
    }

    public static class Browser {
        private String mode = "DISABLED";
        private String executablePath;
        private List<String> launchArguments = List.of();
        private Duration actionTimeout = Duration.ofSeconds(15);
        private int maxPagesPerSession = 10;
        private int maxReadTextCharacters = 30000;
        private int maxConsoleMessages = 200;
        private int maxScreenshotBytes = 1048576;
        private boolean executeJsEnabled = false;
        private java.util.Set<String> allowedHosts = java.util.Set.of();
        private java.util.Set<String> allowedCidrs = java.util.Set.of();
        private String remoteEndpoint;
        private java.util.Map<String, String> remoteHeaders = java.util.Map.of();
        public String getMode() { return mode; }
        public void setMode(String value) { mode = value; }
        public String getExecutablePath() { return executablePath; }
        public void setExecutablePath(String value) { executablePath = value; }
        public List<String> getLaunchArguments() { return launchArguments; }
        public void setLaunchArguments(List<String> value) { launchArguments = value; }
        public Duration getActionTimeout() { return actionTimeout; }
        public void setActionTimeout(Duration value) { actionTimeout = value; }
        public int getMaxPagesPerSession() { return maxPagesPerSession; }
        public void setMaxPagesPerSession(int value) { maxPagesPerSession = value; }
        public int getMaxReadTextCharacters() { return maxReadTextCharacters; }
        public void setMaxReadTextCharacters(int value) { maxReadTextCharacters = value; }
        public int getMaxConsoleMessages() { return maxConsoleMessages; }
        public void setMaxConsoleMessages(int value) { maxConsoleMessages = value; }
        public int getMaxScreenshotBytes() { return maxScreenshotBytes; }
        public void setMaxScreenshotBytes(int value) { maxScreenshotBytes = value; }
        public boolean isExecuteJsEnabled() { return executeJsEnabled; }
        public void setExecuteJsEnabled(boolean value) { executeJsEnabled = value; }
        public java.util.Set<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(java.util.Set<String> value) { allowedHosts = value; }
        public java.util.Set<String> getAllowedCidrs() { return allowedCidrs; }
        public void setAllowedCidrs(java.util.Set<String> value) { allowedCidrs = value; }
        public String getRemoteEndpoint() { return remoteEndpoint; }
        public void setRemoteEndpoint(String value) { remoteEndpoint = value; }
        public java.util.Map<String, String> getRemoteHeaders() { return remoteHeaders; }
        public void setRemoteHeaders(java.util.Map<String, String> value) { remoteHeaders = value; }
    }

    // Getters and Setters
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getFlowId() { return flowId; }
    public void setFlowId(String flowId) { this.flowId = flowId; }
    public boolean isUseDefaultFlow() { return useDefaultFlow; }
    public void setUseDefaultFlow(boolean useDefaultFlow) { this.useDefaultFlow = useDefaultFlow; }
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
    public Skills getSkills() { return skills; }
    public void setSkills(Skills skills) { this.skills = skills; }
    public Browser getBrowser() { return browser; }
    public void setBrowser(Browser browser) { this.browser = browser; }
}
