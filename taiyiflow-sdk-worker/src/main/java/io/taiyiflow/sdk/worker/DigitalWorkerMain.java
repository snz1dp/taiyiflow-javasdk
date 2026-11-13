package io.taiyiflow.sdk.worker;

import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.worker.auth.EncryptedFileCredentialStore;
import io.taiyiflow.sdk.worker.auth.WorkerAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DigitalWorkerMain {
    private static final Logger log = LoggerFactory.getLogger(DigitalWorkerMain.class);

    private DigitalWorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        WorkerConfig config = new WorkerConfigLoader().load(configPath(args));
        DigitalWorker worker = build(config, new EnvironmentSecretProvider());
        AtomicBoolean stopped = new AtomicBoolean();
        Runnable stop = () -> stopOnce(worker, stopped);
        Runtime.getRuntime().addShutdownHook(new Thread(stop, "taiyiflow-worker-shutdown"));
        try {
            worker.start();
            worker.await();
        } finally {
            stop.run();
        }
    }

    static DigitalWorker build(WorkerConfig config, SecretProvider secrets) throws Exception {
        if (config.auth() == null || config.auth().type() == null) {
            throw new IllegalArgumentException("auth.type is required");
        }
        if (config.identity() == null) {
            throw new IllegalArgumentException("identity is required");
        }
        WorkerAuth auth = createAuth(config, secrets);
        WorkerConfig.Identity identity = config.identity();
        WorkerConfig.Execution execution = config.execution();
        WorkerConfig.Extensions extensions = config.extensions();
        Ability[] abilities = identity.abilities().stream().map(Ability::of).toArray(Ability[]::new);
        DigitalWorker.Builder builder = DigitalWorker.builder()
                .baseUrl(config.baseUrl())
                .auth(auth)
                .displayName(identity.displayName())
                .role(identity.role())
                .abilities(abilities)
                .workspace(config.workspace())
                .stateDir(config.stateDir())
                .outboxLimits(new io.taiyiflow.sdk.core.report.ReportOutbox.Limits(
                        config.outbox().maxEntries(), config.outbox().maxEntryBytes(),
                        config.outbox().maxTotalBytes(), config.outbox().maxRetries()))
                .clientId(config.clientId())
                .deviceId(config.deviceId())
                .concurrency(execution.concurrency())
                .connectTimeout(execution.connectTimeout())
                .drainTimeout(execution.drainTimeout())
                .requestTimeout(execution.requestTimeout())
                .recoveryPolicy(config.recoveryPolicy())
                .permissionPolicy(config.permissionPolicy())
                .approvalChannel(config.approval().channel())
                .identityValidator(instantiateRequired(
                        extensions.identityValidator(), WorkerIdentityValidator.class, "extensions.identity-validator"));
        if (identity.workerId() != null && !identity.workerId().isBlank()) {
            builder.identity(WorkerIdentity.of(identity.workerId(), identity.displayName()));
        }
        if (extensions.workerRegistrar() != null && !extensions.workerRegistrar().isBlank()) {
            builder.workerRegistrar(instantiate(extensions.workerRegistrar(), WorkerRegistrar.class));
        }
        if (extensions.taskHandler() != null && !extensions.taskHandler().isBlank()) {
            builder.onTask(instantiate(extensions.taskHandler(), WorkerTaskHandler.class));
        }
        if (extensions.taskReporter() != null && !extensions.taskReporter().isBlank()) {
            builder.taskReporter(instantiate(extensions.taskReporter(), TaskReporter.class));
        }
        if (extensions.approvalHandler() != null && !extensions.approvalHandler().isBlank()) {
            builder.approvalHandler(instantiate(extensions.approvalHandler(), PermissionHook.class));
        }
        if (extensions.approvalAudit() != null && !extensions.approvalAudit().isBlank()) {
            builder.approvalAudit(instantiate(extensions.approvalAudit(), ApprovalAudit.class));
        }
        for (String hook : extensions.hooks()) {
            builder.onWorker(instantiate(hook, WorkerHook.class));
        }
        return builder.build();
    }

    private static WorkerAuth createAuth(WorkerConfig config, SecretProvider secrets) throws Exception {
        WorkerConfig.Auth auth = config.auth();
        String secretName = auth.tokenSecret();
        String token = secretName == null || secretName.isBlank() ? null : secrets.get(secretName);
        return switch (auth.type()) {
            case API_KEY -> WorkerAuth.apiKey(requireSecret(token, secretName));
            case JWT -> WorkerAuth.jwt(requireSecret(token, secretName));
            case PERSISTENT_TOKEN -> {
                if (auth.credentialFile() == null || auth.keyFile() == null) {
                    throw new IllegalArgumentException(
                            "auth.credential-file and auth.key-file are required for persistent tokens");
                }
                String deviceId = auth.deviceId() == null ? config.deviceId() : auth.deviceId();
                yield WorkerAuth.persistentToken(token, deviceId,
                        new EncryptedFileCredentialStore(
                                auth.credentialFile(), auth.keyFile(), config.workspace()));
            }
        };
    }

    private static String requireSecret(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Secret is missing or blank: " + name);
        }
        return value;
    }

    private static <T> T instantiateRequired(String className, Class<T> type, String setting) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException(setting + " is required");
        }
        return instantiate(className, type);
    }

    private static <T> T instantiate(String className, Class<T> type) {
        try {
            return type.cast(Class.forName(className).getConstructor().newInstance());
        } catch (ReflectiveOperationException | ClassCastException exception) {
            throw new IllegalArgumentException("Unable to load " + type.getSimpleName() + ": " + className, exception);
        }
    }

    private static Path configPath(String[] args) {
        if (args.length == 2 && "--config".equals(args[0])) {
            return Path.of(args[1]);
        }
        if (args.length == 1 && !args[0].isBlank()) {
            return Path.of(args[0]);
        }
        if (args.length != 0) {
            throw new IllegalArgumentException("Usage: DigitalWorkerMain [--config <worker.yml>]");
        }
        String configured = System.getProperty("taiyiflow.worker.config");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("TAIYIFLOW_WORKER_CONFIG");
        }
        return Path.of(configured == null || configured.isBlank() ? "worker.yml" : configured);
    }

    private static void stopOnce(DigitalWorker worker, AtomicBoolean stopped) {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.stop();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Digital worker shutdown was interrupted");
        } catch (Exception exception) {
            log.warn("Digital worker shutdown failed", exception);
        }
    }
}
