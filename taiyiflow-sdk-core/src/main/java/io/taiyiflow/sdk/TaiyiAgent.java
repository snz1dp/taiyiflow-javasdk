package io.taiyiflow.sdk;

import io.taiyiflow.sdk.collaboration.CollaborationPlugin;
import io.taiyiflow.sdk.core.dispatch.ToolRuntime;
import io.taiyiflow.sdk.core.fs.WorkspacePaths;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.core.report.ReportOutbox;
import io.taiyiflow.sdk.core.session.AgentSessionImpl;
import io.taiyiflow.sdk.core.session.ClientIdentity;
import io.taiyiflow.sdk.core.session.SessionManager;
import io.taiyiflow.sdk.core.skill.SkillSupplier;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import io.taiyiflow.sdk.transport.envelope.FlowMetadata;
import io.taiyiflow.sdk.transport.rest.FlowMetadataClient;
import io.taiyiflow.sdk.transport.rest.FlowRunClient;
import io.taiyiflow.sdk.transport.rest.CollaborationTaskClient;
import io.taiyiflow.sdk.transport.rest.ConfigClient;
import io.taiyiflow.sdk.transport.rest.ExecutionController;
import io.taiyiflow.sdk.transport.rest.FileClient;
import io.taiyiflow.sdk.transport.rest.McpClient;
import io.taiyiflow.sdk.transport.rest.MemoryClient;
import io.taiyiflow.sdk.transport.rest.ModelsClient;
import io.taiyiflow.sdk.transport.rest.RestClient;
import io.taiyiflow.sdk.transport.rest.ScheduleClient;
import io.taiyiflow.sdk.transport.rest.ServiceConfig;
import io.taiyiflow.sdk.transport.rest.SessionClient;
import io.taiyiflow.sdk.transport.rest.SessionGroupClient;
import io.taiyiflow.sdk.transport.rest.SkillClient;
import io.taiyiflow.sdk.transport.stream.StreamProtocolResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Main entry point for the TaiyiFlow Java SDK.
 *
 * <p>Usage:</p>
 * <pre>
 * TaiyiAgent agent = TaiyiAgent.builder()
 *     .baseUrl("https://api.taiyiflow.io")
 *     .apiKey("your-api-key")
 *     .flowId("your-flow-id")
 *     .build();
 *
 * AgentSession session = agent.newSession();
 * session.onStream(new StreamHook() {
 *     public void onChunk(String sessionId, String chunk) {
 *         System.out.print(chunk);
 *     }
 * });
 * session.chat("Hello, agent!");
 * </pre>
 */
public class TaiyiAgent {

    private static final Logger log = LoggerFactory.getLogger(TaiyiAgent.class);

    private final String baseUrl;
    private final Supplier<String> accessToken;
    private final String flowId;
    private final String clientId;
    private final String deviceId;
    private final SessionManager sessionManager;
    private final PluginManager pluginManager;
    private final ToolRuntime toolRuntime;
    private final boolean ownsToolRuntime;
    private final RestClient restClient;
    private final ExecutionController executionController;
    private final StreamProtocolResolver streamProtocolResolver;
    private final List<StreamHook> globalStreamHooks;
    private final List<ToolExecutionHook> globalToolHooks;
    private final List<PermissionHook> globalPermissionHooks;
    private final List<SessionHook> globalSessionHooks;
    private final ExecutorService executor;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration streamIdleTimeout;
    private final Duration toolExecutionTimeout;
    private final Map<String, String> credentialHeaders;
    private final PermissionPolicy permissionPolicy;
    private final PermissionHook permissionHook;
    private final io.taiyiflow.sdk.core.security.PermissionGate permissionGate;
    private final Duration permissionTimeout;
    private final int eventQueueCapacity;
    private final boolean sseFallbackEnabled;
    private final FlowAdapter flowAdapter;
    private final RecoveryPolicy recoveryPolicy;
    private final Duration recoveryMinBackoff;
    private final Duration recoveryMaxBackoff;
    private final int recoveryMaxAttempts;
    private final boolean autoRestartOnExecutionLoss;
    private final CollaborationPlugin collaborationPlugin;
    private final Path workspace;
    private final FlowMetadataClient flowMetadataClient;
    private final SkillSupplier skillSupplier;
    private final List<String> defaultSkillNames;
    private final RequestDefaults requestDefaults;
    private final List<Path> instructionDirectories;
    private final Integer maxSessionConcurrency;
    private final String clientType;
    private volatile FlowMetadata flowMetadata;
    private final Object metadataLock = new Object();
    private volatile ServiceConfig serverConfig;
    private volatile boolean serverConfigResolved;
    private final Object serverConfigLock = new Object();
    private volatile long serverConfigRetryAfterNanos;
    static volatile long serverConfigNegativeCacheMs = 30_000;

    private TaiyiAgent(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.accessToken = builder.accessToken;
        this.flowId = builder.flowId;
        this.clientId = builder.clientId != null ? builder.clientId : ClientIdentity.loadOrCreate();
        this.deviceId = builder.deviceId != null ? builder.deviceId : builder.collaborationDeviceId;
        this.connectTimeout = builder.connectTimeout;
        this.requestTimeout = builder.requestTimeout;
        this.streamIdleTimeout = builder.streamIdleTimeout;
        this.toolExecutionTimeout = builder.toolExecutionTimeout;
        this.credentialHeaders = Map.copyOf(builder.credentialHeaders);
        this.permissionPolicy = builder.permissionPolicy;
        this.permissionHook = builder.permissionHook;
        this.permissionGate = new io.taiyiflow.sdk.core.security.PermissionGate(
                builder.permissionSettings != null ? builder.permissionSettings
                        : io.taiyiflow.sdk.core.security.PermissionSettings.builder()
                                .policy(builder.permissionPolicy).build());
        this.permissionTimeout = builder.permissionTimeout;
        this.eventQueueCapacity = builder.eventQueueCapacity;
        this.sseFallbackEnabled = builder.sseFallbackEnabled;
        this.flowAdapter = builder.flowAdapter;
        this.recoveryPolicy = builder.recoveryPolicy;
        this.recoveryMinBackoff = builder.recoveryMinBackoff;
        this.recoveryMaxBackoff = builder.recoveryMaxBackoff;
        this.recoveryMaxAttempts = builder.recoveryMaxAttempts;
        this.autoRestartOnExecutionLoss = builder.autoRestartOnExecutionLoss;
        this.globalStreamHooks = new ArrayList<>(builder.streamHooks);
        this.globalToolHooks = new ArrayList<>(builder.toolHooks);
        this.globalPermissionHooks = new ArrayList<>(builder.permissionHooks);
        this.globalSessionHooks = new ArrayList<>(builder.sessionHooks);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.sessionManager = new SessionManager(executor, builder.maxSessions);
        this.pluginManager = new PluginManager();
        this.ownsToolRuntime = builder.toolRuntime == null;
        this.toolRuntime = ownsToolRuntime
                ? new ToolRuntime(builder.toolRuntimeConfig) : builder.toolRuntime;
        this.restClient = builder.restClient != null ? builder.restClient
                : new RestClient(baseUrl, accessToken, requestTimeout, credentialHeaders);
        this.workspace = builder.workspace == null ? null : builder.workspace.toAbsolutePath().normalize();
        this.flowMetadataClient = new FlowMetadataClient(restClient);
        this.skillSupplier = builder.skillSupplier;
        this.defaultSkillNames = List.copyOf(builder.defaultSkillNames);
        this.requestDefaults = builder.requestDefaults;
        this.instructionDirectories = List.copyOf(builder.instructionDirectories);
        this.maxSessionConcurrency = builder.maxSessionConcurrency;
        this.clientType = builder.clientType;
        this.executionController = new ExecutionController(restClient, clientId, deviceId);
        this.streamProtocolResolver = new StreamProtocolResolver(restClient, builder.streamProtocol);

        if (builder.collaborationEnabled) {
            String collaborationClientId = builder.collaborationClientId != null
                    ? builder.collaborationClientId : clientId;
            String collaborationDeviceId = builder.collaborationDeviceId != null
                    ? builder.collaborationDeviceId : deviceId;
            collaborationPlugin = new CollaborationPlugin(
                    baseUrl, accessToken, CollaborationPlugin.ROLE_COLLABORATION_WORKER,
                    collaborationClientId, collaborationDeviceId,
                    builder.collaborationInstanceId, credentialHeaders);
            collaborationPlugin.start();
        } else {
            collaborationPlugin = null;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a new session for chatting with the agent.
     * Multiple sessions can run concurrently.
     */
    public AgentSession newSession() {
        return sessionManager.createSession(this);
    }

    /**
     * Create a new session with a specific session ID.
     */
    public AgentSession newSession(String sessionId) {
        return sessionManager.createSession(this, sessionId);
    }

    /**
     * Gracefully shut down the agent and all sessions.
     */
    public void shutdown() {
        if (collaborationPlugin != null) {
            collaborationPlugin.stop();
        }
        sessionManager.shutdownAll();
        pluginManager.close();
        executor.shutdown();
        if (ownsToolRuntime) {
            toolRuntime.close();
        }
    }

    // Getters
    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return accessToken.get();
    }

    public Supplier<String> getAccessToken() {
        return accessToken;
    }

    public String getFlowId() {
        return flowId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    /**
     * First-frame client_type; default "device". The backend merges default built-in
     * skills by this value, so changing it (e.g. to "desktop") changes the server-side
     * skill set offered to the same flow.
     */
    public String getClientType() {
        return clientType;
    }

    public RestClient getRestClient() {
        return restClient;
    }

    public SessionClient sessions() {
        return new SessionClient(restClient);
    }

    public ModelsClient models() {
        return new ModelsClient(restClient);
    }

    public SessionGroupClient sessionGroups() {
        return new SessionGroupClient(restClient);
    }

    public io.taiyiflow.sdk.transport.rest.ToolCallRecordClient toolCallRecords() {
        return new io.taiyiflow.sdk.transport.rest.ToolCallRecordClient(restClient);
    }

    public io.taiyiflow.sdk.transport.rest.NotificationClient notifications() {
        return new io.taiyiflow.sdk.transport.rest.NotificationClient(restClient);
    }

    public SkillClient skills() {
        return new SkillClient(restClient);
    }

    public MemoryClient memories() {
        return new MemoryClient(restClient);
    }

    public ScheduleClient schedules() {
        return new ScheduleClient(restClient);
    }

    public ConfigClient config() {
        return new ConfigClient(restClient);
    }

    public io.taiyiflow.sdk.transport.rest.IntentionClient intentions() {
        return new io.taiyiflow.sdk.transport.rest.IntentionClient(restClient);
    }

    public io.taiyiflow.sdk.transport.rest.ClientDirectoryClient clientDirectory() {
        return new io.taiyiflow.sdk.transport.rest.ClientDirectoryClient(restClient);
    }

    public io.taiyiflow.sdk.transport.rest.QuotaCreditClient quotaCredits() {
        return new io.taiyiflow.sdk.transport.rest.QuotaCreditClient(restClient);
    }

    public io.taiyiflow.sdk.transport.rest.PlatformProbeClient probes() {
        return new io.taiyiflow.sdk.transport.rest.PlatformProbeClient(restClient);
    }

    public io.taiyiflow.sdk.transport.rest.AgentAppClient agentApps() {
        return new io.taiyiflow.sdk.transport.rest.AgentAppClient(restClient);
    }

    public io.taiyiflow.sdk.transport.rest.AgentPluginClient agentPlugins() {
        return new io.taiyiflow.sdk.transport.rest.AgentPluginClient(restClient);
    }

    public FileClient files() {
        return new FileClient(restClient);
    }

    public FlowRunClient flowRuns() {
        return new FlowRunClient(restClient);
    }

    public FlowMetadata getFlowMetadata() throws Exception {
        FlowMetadata current = flowMetadata;
        if (current != null) {
            return current;
        }
        synchronized (metadataLock) {
            if (flowMetadata == null) {
                flowMetadata = flowMetadataClient.get(flowId);
            }
            return flowMetadata;
        }
    }

    public FlowMetadata refreshFlowMetadata() throws Exception {
        synchronized (metadataLock) {
            FlowMetadata refreshed = flowMetadataClient.get(flowId);
            flowMetadata = refreshed;
            return refreshed;
        }
    }

    public Path getWorkspace() {
        return workspace;
    }

    public Path workspaceFor(String flowId, String sessionId) throws java.io.IOException {
        return WorkspacePaths.sessionWorkspace(workspace, flowId, sessionId);
    }

    public McpClient mcpServers() {
        return new McpClient(restClient);
    }

    public CollaborationTaskClient collaborationTasks() {
        return new CollaborationTaskClient(restClient);
    }

    public ExecutionController executionControl() {
        return executionController;
    }

    public StreamProtocol resolveStreamProtocol() {
        return streamProtocolResolver.resolve();
    }

    public StreamProtocol refreshStreamProtocol() {
        return streamProtocolResolver.refresh();
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    /** Shared by sessions and available for a worker that owns cross-flow tool resources. */
    public ToolRuntime getToolRuntime() {
        return toolRuntime;
    }

    /** Resume persisted first-frame submissions for this flow without executing any tools. */
    public void flushResumeReports() {
        for (ReportOutbox.Entry entry : toolRuntime.getReportOutbox().listAll()) {
            if (entry.getType() != ReportOutbox.Type.CHECKPOINT_TOOL_RESULTS
                    || !flowId.equals(entry.getFlowId())) {
                continue;
            }
            if (entry.getStatus() == ReportOutbox.Status.DEAD_LETTER) {
                reconcileDeadLetterCheckpoint(entry);
                continue;
            }
            if (!entry.isDue()) {
                continue;
            }
            AgentSessionImpl existing = sessionManager.getSession(entry.getSessionId());
            if (existing != null) {
                continue;
            }
            try {
                AgentSessionImpl session = (AgentSessionImpl) newSession(entry.getSessionId());
                session.recoverReport(entry);
            } catch (RuntimeException exception) {
                log.warn("Unable to resume stored report {} for session {}",
                        entry.getId(), entry.getSessionId());
            }
        }
    }

    private void reconcileDeadLetterCheckpoint(ReportOutbox.Entry entry) {
        try {
            io.taiyiflow.sdk.core.report.PlatformResultReconciler.Verdict verdict =
                    new io.taiyiflow.sdk.core.report.PlatformResultReconciler(toolCallRecords())
                            .classifyCheckpointPayload(entry.getPayload(), flowId,
                                    entry.getSessionId(), entry.getRunId());
            if (verdict != io.taiyiflow.sdk.core.report.PlatformResultReconciler.Verdict.UNKNOWN) {
                toolRuntime.getReportOutbox().delete(entry.getId());
            }
        } catch (Exception exception) {
            log.warn("Unable to reconcile dead-letter checkpoint submission {}", entry.getId());
        }
    }

    public CollaborationPlugin getCollaborationPlugin() {
        return collaborationPlugin;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public List<StreamHook> getGlobalStreamHooks() {
        return globalStreamHooks;
    }

    public List<ToolExecutionHook> getGlobalToolHooks() {
        return globalToolHooks;
    }

    public List<PermissionHook> getGlobalPermissionHooks() {
        return globalPermissionHooks;
    }

    public List<SessionHook> getGlobalSessionHooks() {
        return globalSessionHooks;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    /** Stream idle watchdog timeout; zero disables idle checks. */
    public Duration getStreamIdleTimeout() {
        return streamIdleTimeout;
    }

    /** Explicit tool-period watchdog timeout, or null for registry-derived (max tool time + 60s, floor 31min). */
    public Duration getToolExecutionTimeout() {
        return toolExecutionTimeout;
    }

    public Map<String, String> getCredentialHeaders() {
        return credentialHeaders;
    }

    public PermissionPolicy getPermissionPolicy() {
        return permissionPolicy;
    }

    public PermissionHook getPermissionHook() {
        return permissionHook;
    }

    /** Fine-grained gate used when no explicit permissionHook override is configured. */
    public io.taiyiflow.sdk.core.security.PermissionGate getPermissionGate() {
        return permissionGate;
    }

    public Duration getPermissionTimeout() {
        return permissionTimeout;
    }

    public int getEventQueueCapacity() {
        return eventQueueCapacity;
    }

    public boolean isSseFallbackEnabled() {
        return sseFallbackEnabled;
    }

    public FlowAdapter getFlowAdapter() {
        return flowAdapter;
    }

    public SkillSupplier getSkillSupplier() {
        return skillSupplier;
    }

    public List<String> getDefaultSkillNames() {
        return defaultSkillNames;
    }

    public RequestDefaults getRequestDefaults() {
        return requestDefaults;
    }

    public List<Path> getInstructionDirectories() {
        return instructionDirectories;
    }

    /**
     * Effective limit for concurrently executing sessions started via chat().
     * Explicit builder override wins; otherwise the server /config quota
     * (client_max_session_concurrency, default 3). Returns -1 (unlimited) when
     * /config could not be fetched — a documented deviation from Electron,
     * which always has a cached config.
     */
    public int getMaxSessionConcurrency() {
        if (maxSessionConcurrency != null) {
            return maxSessionConcurrency;
        }
        ServiceConfig config = serverConfig();
        return config != null ? config.clientMaxSessionConcurrency() : -1;
    }

    /** Server auto-loop round quota, or null when unset/unavailable. */
    public Integer getServerAutoLoopMaxRounds() {
        ServiceConfig config = serverConfig();
        return config != null ? config.agentAutoLoopMaxRounds() : null;
    }

    private ServiceConfig serverConfig() {
        if (serverConfigResolved) {
            return serverConfig;
        }
        synchronized (serverConfigLock) {
            if (serverConfigResolved) {
                return serverConfig;
            }
            // A failed /config fetch is cached only briefly so a recovered server is picked up
            // later, instead of pinning session concurrency to unlimited for the agent's lifetime.
            long now = System.nanoTime();
            if (now < serverConfigRetryAfterNanos) {
                return serverConfig;
            }
            try {
                serverConfig = new ConfigClient(restClient).getServiceConfig();
                serverConfigResolved = true;
            } catch (Exception e) {
                log.warn("Unable to load /config quotas; session concurrency stays unlimited: {}",
                        e.getMessage());
                serverConfig = null;
                serverConfigRetryAfterNanos = now + serverConfigNegativeCacheMs * 1_000_000L;
            }
        }
        return serverConfig;
    }

    public RecoveryPolicy getRecoveryPolicy() {
        return recoveryPolicy;
    }

    public Duration getRecoveryMinBackoff() {
        return recoveryMinBackoff;
    }

    public Duration getRecoveryMaxBackoff() {
        return recoveryMaxBackoff;
    }

    public int getRecoveryMaxAttempts() {
        return recoveryMaxAttempts;
    }

    public boolean isAutoRestartOnExecutionLoss() {
        return autoRestartOnExecutionLoss;
    }

    /**
     * Builder for TaiyiAgent.
     */
    public static class Builder {
        private String baseUrl;
        private Supplier<String> accessToken;
        private String flowId;
        private boolean useServerDefaultFlow;
        private Path workspace;
        private String clientId;
        private String deviceId;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private Duration streamIdleTimeout = Duration.ofSeconds(1200);
        private Duration toolExecutionTimeout;
        private int maxSessions = 100;
        private Integer maxSessionConcurrency;
        private String clientType = "device";
        private int eventQueueCapacity = 1024;
        private ToolRuntime toolRuntime;
        private RestClient restClient;
        private SkillSupplier skillSupplier;
        private List<String> defaultSkillNames = List.of();
        private RequestDefaults requestDefaults = RequestDefaults.defaults();
        private List<Path> instructionDirectories = List.of();
        private ToolRuntime.Config toolRuntimeConfig = ToolRuntime.Config.defaults();
        private boolean toolRuntimeConfigSet;
        private boolean sseFallbackEnabled = true;
        private FlowAdapter flowAdapter;
        private boolean collaborationEnabled = false;
        private String collaborationClientId;
        private String collaborationDeviceId;
        private String collaborationInstanceId;
        private Map<String, String> credentialHeaders = Map.of();
        private StreamProtocol streamProtocol = StreamProtocol.AUTO;
        private PermissionPolicy permissionPolicy = PermissionPolicy.READONLY;
        private PermissionHook permissionHook;
        private io.taiyiflow.sdk.core.security.PermissionSettings permissionSettings;
        private Duration permissionTimeout = Duration.ofSeconds(300);
        private RecoveryPolicy recoveryPolicy = RecoveryPolicy.MANUAL;
        private Duration recoveryMinBackoff = Duration.ofSeconds(2);
        private Duration recoveryMaxBackoff = Duration.ofSeconds(60);
        private int recoveryMaxAttempts = 8;
        private boolean autoRestartOnExecutionLoss = true;

        private final List<StreamHook> streamHooks = new ArrayList<>();
        private final List<ToolExecutionHook> toolHooks = new ArrayList<>();
        private final List<PermissionHook> permissionHooks = new ArrayList<>();
        private final List<SessionHook> sessionHooks = new ArrayList<>();

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.accessToken = () -> apiKey;
            return this;
        }

        public Builder accessToken(Supplier<String> accessToken) {
            this.accessToken = java.util.Objects.requireNonNull(accessToken, "accessToken");
            return this;
        }

        public Builder flowId(String flowId) {
            this.flowId = flowId;
            return this;
        }

        public Builder useServerDefaultFlow() {
            this.useServerDefaultFlow = true;
            return this;
        }

        public Builder workspace(Path workspace) {
            this.workspace = java.util.Objects.requireNonNull(workspace, "workspace");
            return this;
        }

        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder deviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        /** First-frame client_type; default "device". Affects server-side built-in skill merging. */
        public Builder clientType(String clientType) {
            if (clientType == null || clientType.isBlank()) {
                throw new IllegalArgumentException("clientType must not be blank");
            }
            this.clientType = clientType;
            return this;
        }

        public Builder connectTimeout(Duration timeout) {
            this.connectTimeout = timeout;
            return this;
        }

        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = timeout;
            return this;
        }

        /** Stream idle watchdog timeout; zero disables idle checks. Default 1200s. */
        public Builder streamIdleTimeout(Duration timeout) {
            if (timeout == null || timeout.isNegative()) {
                throw new IllegalArgumentException("streamIdleTimeout must be >= 0 (0 disables idle checks)");
            }
            this.streamIdleTimeout = timeout;
            return this;
        }

        /**
         * Tool-period watchdog timeout override. Values below the registry-derived floor
         * (max registered tool time + 60s, never below 31min) are raised to that floor.
         * Null keeps automatic derivation.
         */
        public Builder toolExecutionTimeout(Duration timeout) {
            if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
                throw new IllegalArgumentException("toolExecutionTimeout must be positive");
            }
            this.toolExecutionTimeout = timeout;
            return this;
        }

        /** Inject worker-owned runtime resources; the built agent will not close them. */
        public Builder toolRuntime(ToolRuntime runtime) {
            this.toolRuntime = java.util.Objects.requireNonNull(runtime, "toolRuntime");
            return this;
        }

        public Builder skillSupplier(SkillSupplier supplier, List<String> defaultSkillNames) {
            this.skillSupplier = supplier;
            this.defaultSkillNames = defaultSkillNames == null ? List.of() : List.copyOf(defaultSkillNames);
            return this;
        }

        /** Agent-level first-frame defaults; explicit per-request values always win. */
        public Builder requestDefaults(RequestDefaults requestDefaults) {
            this.requestDefaults = java.util.Objects.requireNonNull(requestDefaults, "requestDefaults");
            return this;
        }

        /**
         * Opt in to client_instructions supply from explicit directories only.
         * No managed/user/home discovery is performed; empty (the default) disables supply.
         */
        public Builder instructionDirectories(List<Path> directories) {
            this.instructionDirectories = directories == null ? List.of()
                    : directories.stream().map(dir -> dir.toAbsolutePath().normalize()).toList();
            return this;
        }

        /** Advanced transport injection, primarily for worker authentication retry integration. */
        public Builder restClient(RestClient restClient) {
            this.restClient = java.util.Objects.requireNonNull(restClient, "restClient");
            return this;
        }

        /** Configure the standalone runtime created and closed by the built agent. */
        public Builder toolRuntimeConfig(ToolRuntime.Config config) {
            this.toolRuntimeConfig = java.util.Objects.requireNonNull(config, "toolRuntimeConfig");
            this.toolRuntimeConfigSet = true;
            return this;
        }

        public Builder maxSessions(int max) {
            this.maxSessions = max;
            return this;
        }

        /**
         * Explicit limit for concurrently executing sessions (new chat() starts only;
         * resume/attach/retry/repeat are exempt). Null (default) defers to the server
         * /config quota; 0 blocks new chats.
         */
        public Builder maxSessionConcurrency(Integer limit) {
            if (limit != null && limit < 0) {
                throw new IllegalArgumentException("maxSessionConcurrency must be >= 0");
            }
            this.maxSessionConcurrency = limit;
            return this;
        }

        public Builder eventQueueCapacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("Event queue capacity must be positive");
            }
            this.eventQueueCapacity = capacity;
            return this;
        }

        public Builder sseFallbackEnabled(boolean enabled) {
            this.sseFallbackEnabled = enabled;
            return this;
        }

        public Builder flowAdapter(FlowAdapter adapter) {
            this.flowAdapter = adapter;
            return this;
        }

        public Builder credentialHeaders(Map<String, String> headers) {
            this.credentialHeaders = Map.copyOf(headers);
            return this;
        }

        public Builder streamProtocol(StreamProtocol protocol) {
            this.streamProtocol = java.util.Objects.requireNonNull(protocol, "protocol");
            return this;
        }

        public Builder permissionPolicy(PermissionPolicy policy) {
            this.permissionPolicy = java.util.Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder permissionHook(PermissionHook hook) {
            this.permissionHook = java.util.Objects.requireNonNull(hook, "hook");
            return this;
        }

        /** Fine-grained permission settings (rules/categories/write-confirmation/browser mode). */
        public Builder permissionSettings(io.taiyiflow.sdk.core.security.PermissionSettings settings) {
            this.permissionSettings = java.util.Objects.requireNonNull(settings, "settings");
            return this;
        }

        public Builder permissionTimeout(Duration timeout) {
            if (timeout == null || timeout.isNegative()
                    || (!timeout.isZero() && (timeout.compareTo(Duration.ofSeconds(10)) < 0
                    || timeout.compareTo(Duration.ofSeconds(6000)) > 0))) {
                throw new IllegalArgumentException("Permission timeout must be 0 or between 10 and 6000 seconds");
            }
            this.permissionTimeout = timeout;
            return this;
        }

        public Builder recoveryPolicy(RecoveryPolicy policy) {
            this.recoveryPolicy = java.util.Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder recoveryBackoff(Duration min, Duration max) {
            if (min == null || max == null || min.isZero() || min.isNegative()
                    || max.isZero() || max.isNegative() || min.compareTo(max) > 0) {
                throw new IllegalArgumentException("Recovery backoff requires 0 < min <= max");
            }
            this.recoveryMinBackoff = min;
            this.recoveryMaxBackoff = max;
            return this;
        }

        public Builder recoveryMaxAttempts(int attempts) {
            if (attempts <= 0) {
                throw new IllegalArgumentException("Recovery max attempts must be positive");
            }
            this.recoveryMaxAttempts = attempts;
            return this;
        }

        public Builder autoRestartOnExecutionLoss(boolean enabled) {
            this.autoRestartOnExecutionLoss = enabled;
            return this;
        }

        public Builder enableCollaboration() {
            this.collaborationEnabled = true;
            return this;
        }

        public Builder collaborationClientId(String clientId) {
            this.collaborationClientId = clientId;
            return this;
        }

        public Builder collaborationDeviceId(String deviceId) {
            this.collaborationDeviceId = deviceId;
            return this;
        }

        public Builder collaborationInstanceId(String instanceId) {
            this.collaborationInstanceId = instanceId;
            return this;
        }

        /**
         * Register a global StreamHook that applies to all sessions.
         */
        public Builder onStream(StreamHook hook) {
            this.streamHooks.add(hook);
            return this;
        }

        /**
         * Register a global ToolExecutionHook that applies to all sessions.
         */
        public Builder onToolExecution(ToolExecutionHook hook) {
            this.toolHooks.add(hook);
            return this;
        }

        /**
         * Register a global PermissionHook that applies to all sessions.
         */
        public Builder onPermission(PermissionHook hook) {
            this.permissionHooks.add(hook);
            return this;
        }

        /**
         * Register a global SessionHook that applies to all sessions.
         */
        public Builder onLifecycle(SessionHook hook) {
            this.sessionHooks.add(hook);
            return this;
        }

        public TaiyiAgent build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            if (accessToken == null) {
                throw new IllegalArgumentException("apiKey or accessToken is required");
            }
            if (useServerDefaultFlow && flowId != null && !flowId.isBlank()) {
                throw new IllegalArgumentException("flowId and useServerDefaultFlow are mutually exclusive");
            }
            if (useServerDefaultFlow || flowId == null || flowId.isBlank()) {
                RestClient configRest = restClient != null ? restClient
                        : new RestClient(baseUrl, accessToken, requestTimeout, credentialHeaders);
                try {
                    flowId = new ConfigClient(configRest).getServiceConfig().defaultFlowId();
                } catch (Exception exception) {
                    throw new IllegalArgumentException("Unable to resolve the server default flow", exception);
                }
            }
            if (toolRuntime != null && toolRuntimeConfigSet) {
                throw new IllegalArgumentException(
                        "toolRuntime and toolRuntimeConfig are mutually exclusive");
            }
            if (toolRuntime != null && toolRuntime.isClosed()) {
                throw new IllegalArgumentException("toolRuntime is closed");
            }
            return new TaiyiAgent(this);
        }
    }
}
