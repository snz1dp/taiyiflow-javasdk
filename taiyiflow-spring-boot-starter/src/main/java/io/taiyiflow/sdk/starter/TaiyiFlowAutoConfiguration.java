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
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring Boot auto-configuration for TaiyiFlow SDK.
 */
@AutoConfiguration
@EnableConfigurationProperties(TaiyiFlowProperties.class)
@ConditionalOnProperty(prefix = "taiyiflow", name = {"base-url", "api-key"})
@Conditional(TaiyiFlowAutoConfiguration.FlowSelectionCondition.class)
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
        log.info("Auto-configuring TaiyiAgent with flow selection: {}",
                properties.isUseDefaultFlow() ? "server-default" : properties.getFlowId());

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

        Path sandbox = Path.of(properties.getSandboxRoot());
        Files.createDirectories(sandbox);
        builder.workspace(sandbox);
        if (properties.isUseDefaultFlow()) {
            builder.useServerDefaultFlow();
        }
        configureSkills(builder, properties, sandbox);
        TaiyiAgent agent = builder.build();

        // Register built-in plugins

        if (properties.getPlugins().isShell()) {
            ShellPlugin.register(agent.getPluginManager(), sandbox, properties.getAllowedShellCommands());
        }
        if (properties.getPlugins().isFiles()) {
            FilePlugin.register(agent.getPluginManager(), sandbox);
        }
        if (properties.getPlugins().isSearch()) {
            SearchPlugin.register(agent.getPluginManager(), sandbox);
        }
        if (properties.getPlugins().isMedia()) {
            io.taiyiflow.sdk.plugins.BuiltinPlugins.registerMedia(agent.getPluginManager(), agent.getRestClient());
        }
        if (properties.getSkills().isEnabled()) {
            io.taiyiflow.sdk.plugins.BuiltinPlugins.registerSkills(agent.getPluginManager(), agent.getSkillSupplier(),
                    skillManager(agent, properties, sandbox), properties.getSkills().getDefaultNames());
        }

        log.info("TaiyiAgent configured with plugins: shell={}, files={}, search={}",
                properties.getPlugins().isShell(),
                properties.getPlugins().isFiles(),
                properties.getPlugins().isSearch());

        return agent;
    }

    private static void configureSkills(TaiyiAgent.Builder builder, TaiyiFlowProperties properties, Path sandbox) {
        TaiyiFlowProperties.Skills skills = properties.getSkills();
        if (!skills.isEnabled()) return;
        Path workspace = skills.getWorkspaceDir() == null ? sandbox.resolve(".snz1dp/skills")
                : Path.of(skills.getWorkspaceDir());
        var registry = io.taiyiflow.sdk.core.skill.SkillRegistry.builder()
                .directory(workspace, io.taiyiflow.sdk.core.skill.SkillSource.WORKSPACE)
                .directory(skills.getUserDir() == null ? null : Path.of(skills.getUserDir()),
                        io.taiyiflow.sdk.core.skill.SkillSource.USER)
                .directory(skills.getCompatDir() == null ? null : Path.of(skills.getCompatDir()),
                        io.taiyiflow.sdk.core.skill.SkillSource.COMPAT)
                .bundled(TaiyiFlowAutoConfiguration.class.getClassLoader(), skills.getBundledResources());
        if (skills.isPlatformEnabled()) {
            registry.platform(new io.taiyiflow.sdk.transport.rest.SkillClient(
                    new io.taiyiflow.sdk.transport.rest.RestClient(properties.getBaseUrl(), properties.getApiKey(),
                            properties.getRequestTimeout(), java.util.Map.of())));
        }
        builder.skillSupplier(new io.taiyiflow.sdk.core.skill.SkillSupplier(registry.build(),
                new io.taiyiflow.sdk.core.skill.SkillSupplyPolicy(true, skills.isPlatformEnabled(),
                        skills.isToleratePlatformFailure())), skills.getDefaultNames());
    }

    private static io.taiyiflow.sdk.plugins.skill.SkillManager skillManager(
            TaiyiAgent agent, TaiyiFlowProperties properties, Path sandbox) {
        TaiyiFlowProperties.Skills skills = properties.getSkills();
        Path workspace = skills.getWorkspaceDir() == null ? sandbox.resolve(".snz1dp/skills")
                : Path.of(skills.getWorkspaceDir());
        return new io.taiyiflow.sdk.plugins.skill.SkillManager(workspace,
                skills.getUserDir() == null ? null : Path.of(skills.getUserDir()), agent.skills());
    }

    static final class FlowSelectionCondition extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String flowId = context.getEnvironment().getProperty("taiyiflow.flow-id");
            boolean useDefault = context.getEnvironment().getProperty(
                    "taiyiflow.use-default-flow", Boolean.class, false);
            boolean explicitFlow = flowId != null && !flowId.isBlank();
            return explicitFlow || useDefault
                    ? ConditionOutcome.match("A flow id or the server default flow is configured")
                    : ConditionOutcome.noMatch("Neither taiyiflow.flow-id nor taiyiflow.use-default-flow is enabled");
        }
    }

}
