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

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Base URL of the TaiyiFlow platform.
     */
    private String baseUrl;

    /**
     * API key for authentication.
     */
    private String apiKey;

    /**
     * Optional Flow ID or name; defaults to the server default Flow.
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

    /** Stream idle watchdog timeout; 0 disables idle checks. */
    private Duration streamIdleTimeout = Duration.ofSeconds(1200);

    /** Tool-period watchdog override; null = derive from registered tools (+60s, floor 31min). */
    private Duration toolExecutionTimeout;

    /**
     * Maximum concurrent sessions.
     */
    private int maxSessions = 100;

    /** Explicit new-chat concurrency limit; null defers to the server /config quota (default 3). */
    private Integer maxSessionConcurrency;

    private int eventQueueCapacity = 1024;

    private boolean sseFallbackEnabled = true;

    private RecoveryPolicy recoveryPolicy = RecoveryPolicy.MANUAL;

    private Duration recoveryMinBackoff = Duration.ofSeconds(2);

    private Duration recoveryMaxBackoff = Duration.ofSeconds(60);

    private int recoveryMaxAttempts = 8;

    private boolean autoRestartOnExecutionLoss = true;

    private Duration permissionTimeout = Duration.ofSeconds(300);

    /** Global permission policy; fine-grained overrides live under {@code taiyiflow.permission.*}. */
    private io.taiyiflow.sdk.hooks.PermissionPolicy permissionPolicy = io.taiyiflow.sdk.hooks.PermissionPolicy.READONLY;

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
    private Request request = new Request();
    private Instructions instructions = new Instructions();
    private Permission permission = new Permission();

    /**
     * Fine-grained permission settings ({@code taiyiflow.permission.*} / {@code taiyiflow.worker.permission.*}).
     * Values bind only from application configuration; skill content and model arguments cannot alter them.
     */
    public static class Permission {
        private List<Rule> rules = List.of();
        private String writeConfirmation;
        private String browserPermissionMode;
        private java.util.Map<String, String> categories = java.util.Map.of();

        public io.taiyiflow.sdk.core.security.PermissionSettings toSettings(
                io.taiyiflow.sdk.hooks.PermissionPolicy policy) {
            var builder = io.taiyiflow.sdk.core.security.PermissionSettings.builder()
                    .policy(policy == null ? io.taiyiflow.sdk.hooks.PermissionPolicy.READONLY : policy)
                    .writeConfirmation(io.taiyiflow.sdk.core.security.WriteConfirmation.of(writeConfirmation))
                    .browserPermissionMode(io.taiyiflow.sdk.core.security.BrowserPermissionMode.of(browserPermissionMode));
            for (Rule rule : rules) {
                builder.addRule(io.taiyiflow.sdk.core.security.PermissionRule.of(
                        rule.getAction(), rule.getToolName(), rule.getCommandPrefix()));
            }
            categories.forEach((key, value) -> {
                io.taiyiflow.sdk.core.security.ToolCategory category =
                        io.taiyiflow.sdk.core.security.ToolCategory.fromKey(key);
                io.taiyiflow.sdk.hooks.PermissionPolicy categoryPolicy =
                        io.taiyiflow.sdk.hooks.PermissionPolicy.fromKey(value);
                if (category != null && categoryPolicy != null) {
                    builder.categoryPolicy(category, categoryPolicy);
                }
            });
            return builder.build();
        }

        public List<Rule> getRules() { return rules; }
        public void setRules(List<Rule> value) { rules = value; }
        public String getWriteConfirmation() { return writeConfirmation; }
        public void setWriteConfirmation(String value) { writeConfirmation = value; }
        public String getBrowserPermissionMode() { return browserPermissionMode; }
        public void setBrowserPermissionMode(String value) { browserPermissionMode = value; }
        public java.util.Map<String, String> getCategories() { return categories; }
        public void setCategories(java.util.Map<String, String> value) { categories = value; }

        public static class Rule {
            private String action;
            private String toolName;
            private String commandPrefix;
            public String getAction() { return action; }
            public void setAction(String value) { action = value; }
            public String getToolName() { return toolName; }
            public void setToolName(String value) { toolName = value; }
            public String getCommandPrefix() { return commandPrefix; }
            public void setCommandPrefix(String value) { commandPrefix = value; }
        }
    }

    /** First-frame request defaults ({@code taiyiflow.request.*}); explicit chat values always win. */
    public static class Request {
        private String requestType;
        private String inputType = "chat";
        private String outputType = "chat";
        private String model;
        private Boolean reasoning;
        private Boolean internetSearch;
        private List<String> knowledgeScopes = List.of();
        private List<String> knowledgeDocs = List.of();
        private boolean autoLoopEnabled = false;
        private Integer autoLoopMaxRounds;
        private String imageGeneration = "AUTO";
        private String groupId;
        private List<String> relationSessions = List.of();
        private String agentAppId;
        private String agentAppOwnerId;
        private String clientContextDir;
        private Boolean longTermMemoryEnabled;
        private Boolean crossSessionMemoryEnabled;
        private String clientType;

        public io.taiyiflow.sdk.RequestDefaults toRequestDefaults() {
            io.taiyiflow.sdk.RequestDefaults.Builder builder = io.taiyiflow.sdk.RequestDefaults.builder()
                    .inputType(inputType)
                    .outputType(outputType)
                    .autoLoopEnabled(autoLoopEnabled)
                    .imageGeneration(io.taiyiflow.sdk.RequestDefaults.ImageGeneration.valueOf(
                            imageGeneration.toUpperCase(java.util.Locale.ROOT)))
                    .relationSessions(relationSessions);
            if (requestType != null) builder.requestType(requestType);
            if (model != null) builder.model(model);
            if (reasoning != null) builder.reasoning(reasoning);
            if (internetSearch != null) builder.internetSearch(internetSearch);
            if (!knowledgeScopes.isEmpty()) builder.knowledgeScopes(JSON.valueToTree(knowledgeScopes));
            if (!knowledgeDocs.isEmpty()) builder.knowledgeDocs(JSON.valueToTree(knowledgeDocs));
            if (autoLoopMaxRounds != null) builder.autoLoopMaxRounds(autoLoopMaxRounds);
            if (groupId != null) builder.groupId(groupId);
            if (agentAppId != null) builder.agentAppId(agentAppId);
            if (agentAppOwnerId != null) builder.agentAppOwnerId(agentAppOwnerId);
            if (clientContextDir != null) builder.clientContextDir(clientContextDir);
            if (longTermMemoryEnabled != null) builder.longTermMemoryEnabled(longTermMemoryEnabled);
            if (crossSessionMemoryEnabled != null) builder.crossSessionMemoryEnabled(crossSessionMemoryEnabled);
            return builder.build();
        }

        public String getRequestType() { return requestType; }
        public void setRequestType(String value) { requestType = value; }
        public String getInputType() { return inputType; }
        public void setInputType(String value) { inputType = value; }
        public String getOutputType() { return outputType; }
        public void setOutputType(String value) { outputType = value; }
        public String getModel() { return model; }
        public void setModel(String value) { model = value; }
        public Boolean getReasoning() { return reasoning; }
        public void setReasoning(Boolean value) { reasoning = value; }
        public Boolean getInternetSearch() { return internetSearch; }
        public void setInternetSearch(Boolean value) { internetSearch = value; }
        public List<String> getKnowledgeScopes() { return knowledgeScopes; }
        public void setKnowledgeScopes(List<String> value) { knowledgeScopes = value; }
        public List<String> getKnowledgeDocs() { return knowledgeDocs; }
        public void setKnowledgeDocs(List<String> value) { knowledgeDocs = value; }
        public boolean isAutoLoopEnabled() { return autoLoopEnabled; }
        public void setAutoLoopEnabled(boolean value) { autoLoopEnabled = value; }
        public Integer getAutoLoopMaxRounds() { return autoLoopMaxRounds; }
        public void setAutoLoopMaxRounds(Integer value) { autoLoopMaxRounds = value; }
        public String getImageGeneration() { return imageGeneration; }
        public void setImageGeneration(String value) { imageGeneration = value; }
        public String getGroupId() { return groupId; }
        public void setGroupId(String value) { groupId = value; }
        public List<String> getRelationSessions() { return relationSessions; }
        public void setRelationSessions(List<String> value) { relationSessions = value; }
        public String getAgentAppId() { return agentAppId; }
        public void setAgentAppId(String value) { agentAppId = value; }
        public String getAgentAppOwnerId() { return agentAppOwnerId; }
        public void setAgentAppOwnerId(String value) { agentAppOwnerId = value; }
        public String getClientContextDir() { return clientContextDir; }
        public void setClientContextDir(String value) { clientContextDir = value; }
        public Boolean getLongTermMemoryEnabled() { return longTermMemoryEnabled; }
        public void setLongTermMemoryEnabled(Boolean value) { longTermMemoryEnabled = value; }
        public Boolean getCrossSessionMemoryEnabled() { return crossSessionMemoryEnabled; }
        public void setCrossSessionMemoryEnabled(Boolean value) { crossSessionMemoryEnabled = value; }
        public String getClientType() { return clientType; }
        public void setClientType(String value) { clientType = value; }
    }

    /** Opt-in client_instructions directories ({@code taiyiflow.instructions.directories}); empty disables supply. */
    public static class Instructions {
        private List<String> directories = List.of();

        public List<java.nio.file.Path> toDirectories() {
            return directories.stream().map(java.nio.file.Path::of).toList();
        }

        public List<String> getDirectories() { return directories; }
        public void setDirectories(List<String> value) { directories = value; }
    }

    public static class Plugins {
        private boolean shell = true;
        private boolean files = true;
        private boolean search = true;
        private boolean media = false;
        private Duration shellCommandTimeout;

        public boolean isShell() { return shell; }
        public void setShell(boolean shell) { this.shell = shell; }
        public boolean isFiles() { return files; }
        public void setFiles(boolean files) { this.files = files; }
        public boolean isSearch() { return search; }
        public void setSearch(boolean search) { this.search = search; }
        public boolean isMedia() { return media; }
        public void setMedia(boolean media) { this.media = media; }
        public Duration getShellCommandTimeout() { return shellCommandTimeout; }
        public void setShellCommandTimeout(Duration shellCommandTimeout) { this.shellCommandTimeout = shellCommandTimeout; }
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
    public Duration getStreamIdleTimeout() { return streamIdleTimeout; }
    public void setStreamIdleTimeout(Duration streamIdleTimeout) { this.streamIdleTimeout = streamIdleTimeout; }
    public Duration getToolExecutionTimeout() { return toolExecutionTimeout; }
    public void setToolExecutionTimeout(Duration toolExecutionTimeout) { this.toolExecutionTimeout = toolExecutionTimeout; }
    public int getMaxSessions() { return maxSessions; }
    public void setMaxSessions(int maxSessions) { this.maxSessions = maxSessions; }
    public Integer getMaxSessionConcurrency() { return maxSessionConcurrency; }
    public void setMaxSessionConcurrency(Integer maxSessionConcurrency) { this.maxSessionConcurrency = maxSessionConcurrency; }
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
    public io.taiyiflow.sdk.hooks.PermissionPolicy getPermissionPolicy() { return permissionPolicy; }
    public void setPermissionPolicy(io.taiyiflow.sdk.hooks.PermissionPolicy permissionPolicy) { this.permissionPolicy = permissionPolicy; }
    public Permission getPermission() { return permission; }
    public void setPermission(Permission permission) { this.permission = permission; }
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
    public Request getRequest() { return request; }
    public void setRequest(Request request) { this.request = request; }
    public Instructions getInstructions() { return instructions; }
    public void setInstructions(Instructions instructions) { this.instructions = instructions; }
}
