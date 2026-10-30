package io.taiyiflow.sdk;

import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.core.session.SessionManager;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import io.taiyiflow.sdk.transport.rest.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final String apiKey;
    private final String flowId;
    private final SessionManager sessionManager;
    private final PluginManager pluginManager;
    private final RestClient restClient;
    private final List<StreamHook> globalStreamHooks;
    private final List<ToolExecutionHook> globalToolHooks;
    private final List<PermissionHook> globalPermissionHooks;
    private final List<SessionHook> globalSessionHooks;
    private final ExecutorService executor;
    private final Duration connectTimeout;
    private final Duration requestTimeout;

    private TaiyiAgent(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.apiKey = builder.apiKey;
        this.flowId = builder.flowId;
        this.connectTimeout = builder.connectTimeout;
        this.requestTimeout = builder.requestTimeout;
        this.globalStreamHooks = new ArrayList<>(builder.streamHooks);
        this.globalToolHooks = new ArrayList<>(builder.toolHooks);
        this.globalPermissionHooks = new ArrayList<>(builder.permissionHooks);
        this.globalSessionHooks = new ArrayList<>(builder.sessionHooks);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.sessionManager = new SessionManager(executor, builder.maxSessions);
        this.pluginManager = new PluginManager();
        this.restClient = new RestClient(baseUrl, apiKey, requestTimeout);

        if (builder.collaborationEnabled) {
            log.info("Collaboration plugin enabled");
            // Collaboration plugin initialization will be added in Phase E
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
        sessionManager.shutdownAll();
        executor.shutdown();
    }

    // Getters
    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getFlowId() {
        return flowId;
    }

    public RestClient getRestClient() {
        return restClient;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
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

    /**
     * Builder for TaiyiAgent.
     */
    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String flowId;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        private int maxSessions = 100;
        private boolean collaborationEnabled = false;

        private final List<StreamHook> streamHooks = new ArrayList<>();
        private final List<ToolExecutionHook> toolHooks = new ArrayList<>();
        private final List<PermissionHook> permissionHooks = new ArrayList<>();
        private final List<SessionHook> sessionHooks = new ArrayList<>();

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder flowId(String flowId) {
            this.flowId = flowId;
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

        public Builder maxSessions(int max) {
            this.maxSessions = max;
            return this;
        }

        public Builder enableCollaboration() {
            this.collaborationEnabled = true;
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
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey is required");
            }
            if (flowId == null || flowId.isBlank()) {
                throw new IllegalArgumentException("flowId is required");
            }
            return new TaiyiAgent(this);
        }
    }
}
