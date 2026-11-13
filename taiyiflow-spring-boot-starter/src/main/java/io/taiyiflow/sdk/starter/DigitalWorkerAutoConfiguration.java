package io.taiyiflow.sdk.starter;

import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.worker.Ability;
import io.taiyiflow.sdk.worker.ApprovalAudit;
import io.taiyiflow.sdk.worker.DigitalWorker;
import io.taiyiflow.sdk.worker.WorkerHook;
import io.taiyiflow.sdk.worker.WorkerIdentity;
import io.taiyiflow.sdk.worker.WorkerIdentityValidator;
import io.taiyiflow.sdk.worker.WorkerTaskHandler;
import io.taiyiflow.sdk.worker.TaskReporter;
import io.taiyiflow.sdk.worker.WorkerRegistrar;
import io.taiyiflow.sdk.worker.auth.WorkerAuth;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

@AutoConfiguration
@ConditionalOnClass(DigitalWorker.class)
@ConditionalOnProperty(prefix = "taiyiflow.worker", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({TaiyiFlowProperties.class, DigitalWorkerProperties.class})
public class DigitalWorkerAutoConfiguration {

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    public DigitalWorker digitalWorker(
            TaiyiFlowProperties platform,
            DigitalWorkerProperties properties,
            ObjectProvider<WorkerAuth> authentication,
            ObjectProvider<WorkerIdentityValidator> identityValidator,
            ObjectProvider<WorkerRegistrar> workerRegistrar,
            ObjectProvider<WorkerTaskHandler> taskHandler,
            ObjectProvider<TaskReporter> taskReporter,
            @Qualifier("workerApprovalHandler") ObjectProvider<PermissionHook> approvalHandler,
            ObjectProvider<ApprovalAudit> approvalAudit,
            ObjectProvider<WorkerHook> workerHooks) {
        WorkerAuth auth = authentication.getIfAvailable(() -> WorkerAuth.apiKey(platform.getApiKey()));
        Ability[] abilities = properties.getAbilities().stream().map(Ability::of).toArray(Ability[]::new);
        DigitalWorker.Builder builder = DigitalWorker.builder()
                .baseUrl(platform.getBaseUrl())
                .auth(auth)
                .displayName(properties.getDisplayName())
                .role(properties.getRole())
                .abilities(abilities)
                .workspace(Path.of(properties.getWorkspace()))
                .stateDir(properties.getStateDir() == null || properties.getStateDir().isBlank()
                        ? null : Path.of(properties.getStateDir()))
                .clientId(properties.getClientId())
                .deviceId(properties.getDeviceId())
                .concurrency(properties.getConcurrency())
                .connectTimeout(properties.getConnectTimeout())
                .drainTimeout(properties.getDrainTimeout())
                .requestTimeout(platform.getRequestTimeout())
                .recoveryPolicy(properties.getRecoveryPolicy())
                .permissionPolicy(properties.getPermissionPolicy())
                .approvalChannel(properties.getApprovalChannel())
                .identityValidator(identityValidator.getIfAvailable());
        if (properties.getWorkerId() != null && !properties.getWorkerId().isBlank()) {
            builder.identity(WorkerIdentity.of(properties.getWorkerId(), properties.getDisplayName()));
        }
        WorkerRegistrar registrar = workerRegistrar.getIfAvailable();
        if (registrar != null) {
            builder.workerRegistrar(registrar);
        }
        WorkerTaskHandler handler = taskHandler.getIfAvailable();
        if (handler != null) {
            builder.onTask(handler);
        }
        TaskReporter reporter = taskReporter.getIfAvailable();
        if (reporter != null) {
            builder.taskReporter(reporter);
        }
        PermissionHook approval = approvalHandler.getIfAvailable();
        if (approval != null) {
            builder.approvalHandler(approval);
        }
        ApprovalAudit audit = approvalAudit.getIfAvailable();
        if (audit != null) {
            builder.approvalAudit(audit);
        }
        workerHooks.orderedStream().forEach(builder::onWorker);
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "taiyiflow.worker", name = "auto-start", havingValue = "true", matchIfMissing = true)
    public DigitalWorkerLifecycle digitalWorkerLifecycle(DigitalWorker worker) {
        return new DigitalWorkerLifecycle(worker);
    }
}
