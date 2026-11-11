package io.taiyiflow.sdk.starter;

import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import io.taiyiflow.sdk.plugins.files.FilePlugin;
import io.taiyiflow.sdk.plugins.search.SearchPlugin;
import io.taiyiflow.sdk.plugins.shell.ShellPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring Boot auto-configuration for TaiyiFlow SDK.
 */
@AutoConfiguration
@EnableConfigurationProperties(TaiyiFlowProperties.class)
@ConditionalOnProperty(prefix = "taiyiflow", name = {"base-url", "api-key", "flow-id"})
public class TaiyiFlowAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TaiyiFlowAutoConfiguration.class);

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public TaiyiAgent taiyiAgent(
            TaiyiFlowProperties properties,
            ObjectProvider<StreamHook> streamHooks,
            ObjectProvider<ToolExecutionHook> toolHooks,
            ObjectProvider<PermissionHook> permissionHooks,
            ObjectProvider<SessionHook> sessionHooks) throws Exception {
        log.info("Auto-configuring TaiyiAgent with flow: {}", properties.getFlowId());

        TaiyiAgent.Builder builder = TaiyiAgent.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .flowId(properties.getFlowId())
                .connectTimeout(properties.getConnectTimeout())
                .requestTimeout(properties.getRequestTimeout())
                .maxSessions(properties.getMaxSessions())
                .eventQueueCapacity(properties.getEventQueueCapacity())
                .sseFallbackEnabled(properties.isSseFallbackEnabled())
                .recoveryPolicy(properties.getRecoveryPolicy())
                .recoveryBackoff(properties.getRecoveryMinBackoff(), properties.getRecoveryMaxBackoff())
                .recoveryMaxAttempts(properties.getRecoveryMaxAttempts())
                .autoRestartOnExecutionLoss(properties.isAutoRestartOnExecutionLoss())
                .permissionTimeout(properties.getPermissionTimeout());

        streamHooks.orderedStream().forEach(builder::onStream);
        toolHooks.orderedStream().forEach(builder::onToolExecution);
        permissionHooks.orderedStream().forEach(builder::onPermission);
        sessionHooks.orderedStream().forEach(builder::onLifecycle);

        if (properties.isCollaborationEnabled()) {
            builder.enableCollaboration();
        }

        TaiyiAgent agent = builder.build();

        // Register built-in plugins
        Path sandbox = Path.of(properties.getSandboxRoot());
        Files.createDirectories(sandbox);

        if (properties.getPlugins().isShell()) {
            ShellPlugin.register(agent.getPluginManager(), sandbox, properties.getAllowedShellCommands());
        }
        if (properties.getPlugins().isFiles()) {
            FilePlugin.register(agent.getPluginManager(), sandbox);
        }
        if (properties.getPlugins().isSearch()) {
            SearchPlugin.register(agent.getPluginManager(), sandbox);
        }

        log.info("TaiyiAgent configured with plugins: shell={}, files={}, search={}",
                properties.getPlugins().isShell(),
                properties.getPlugins().isFiles(),
                properties.getPlugins().isSearch());

        return agent;
    }
}
