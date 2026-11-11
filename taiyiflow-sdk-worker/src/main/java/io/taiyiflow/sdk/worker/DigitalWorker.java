package io.taiyiflow.sdk.worker;

import io.taiyiflow.sdk.RecoveryPolicy;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.collaboration.CollaborationPlugin;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.transport.rest.FileClient;
import io.taiyiflow.sdk.transport.rest.MemoryClient;
import io.taiyiflow.sdk.transport.rest.RestClient;
import io.taiyiflow.sdk.worker.auth.WorkerAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class DigitalWorker {
    private static final Logger log = LoggerFactory.getLogger(DigitalWorker.class);

    private final String baseUrl;
    private final WorkerAuth auth;
    private final WorkerIdentity identity;
    private final WorkerRole role;
    private final Set<Ability> abilities;
    private final Path workspace;
    private final RecoveryPolicy recoveryPolicy;
    private final PermissionPolicy permissionPolicy;
    private final ApprovalChannel approvalChannel;
    private final PermissionHook approvalHook;
    private final List<WorkerHook> workerHooks;
    private final WorkerTaskHandler taskHandler;
    private final AgentSessionTaskHandler agentSessionTaskHandler;
    private final TaskExecutor taskExecutor;
    private final RestClient restClient;
    private final WorkerIdentityValidator identityValidator;
    private final String configuredClientId;
    private final String configuredDeviceId;
    private final Duration connectTimeout;
    private final Duration drainTimeout;
    private final Duration requestTimeout;
    private final WorkerRuntime runtime;
    private final AtomicBoolean stopped = new AtomicBoolean();

    private volatile CollaborationPlugin collaboration;
    private volatile CountDownLatch readyLatch;
    private volatile String instanceId;
    private volatile String clientId;
    private volatile String deviceId;
    private boolean started;

    private DigitalWorker(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.auth = builder.auth;
        this.identity = builder.identity;
        this.role = builder.role;
        this.abilities = Set.copyOf(builder.abilities);
        this.workspace = builder.workspace;
        this.recoveryPolicy = builder.recoveryPolicy;
        this.permissionPolicy = builder.permissionPolicy;
        this.approvalChannel = builder.approvalChannel;
        this.approvalHook = new WorkerApprovalHook(
                permissionPolicy, approvalChannel, builder.approvalHandler, builder.approvalAudit);
        this.workerHooks = List.copyOf(builder.workerHooks);
        this.agentSessionTaskHandler = builder.taskHandler == null
                ? new AgentSessionTaskHandler(this::newAgent) : null;
        this.taskHandler = builder.taskHandler != null ? builder.taskHandler : (task, progress) -> {
            if (isAgentSessionTask(task)) {
                return agentSessionTaskHandler.execute(task, progress);
            }
            throw new IllegalStateException("No worker task handler is configured for " + task.taskKind);
        };
        this.restClient = new WorkerRestClient(baseUrl, auth, builder.requestTimeout, Map.of());
        TaskReporter reporter = builder.taskReporter != null ? builder.taskReporter
                : new PlatformTaskReporter(auth,
                new RestClient(baseUrl, auth::accessToken, builder.requestTimeout, Map.of()));
        this.taskExecutor = new TaskExecutor(taskHandler, reporter);
        this.identityValidator = builder.identityValidator;
        this.configuredClientId = builder.clientId;
        this.configuredDeviceId = builder.deviceId;
        this.connectTimeout = builder.connectTimeout;
        this.drainTimeout = builder.drainTimeout;
        this.requestTimeout = builder.requestTimeout;
        this.runtime = new WorkerRuntime(builder.concurrency);
    }

    public static Builder builder() {
        return new Builder();
    }

    public synchronized void start() throws Exception {
        if (stopped.get()) {
            throw new IllegalStateException("Digital worker has been stopped");
        }
        if (started) {
            throw new IllegalStateException("Digital worker can only be started once");
        }
        started = true;
        try {
            auth.authenticate(baseUrl);
            identityValidator.validate(identity, role, abilities);
            instanceId = role == WorkerRole.PRIMARY_CLI ? InstanceIdStore.loadOrCreate(workspace) : null;
            clientId = configuredClientId != null ? configuredClientId : UUID.randomUUID().toString();
            deviceId = configuredDeviceId != null ? configuredDeviceId : auth.deviceId();
            readyLatch = new CountDownLatch(1);
            collaboration = new CollaborationPlugin(
                    baseUrl, auth::accessToken, role.protocolName(), clientId, deviceId, instanceId, Map.of())
                    .onTaskDispatch(this::dispatch)
                    .onTaskSnapshot(taskExecutor::reconcile)
                    .onConnectionReady(payload -> {
                        runtime.online();
                        readyLatch.countDown();
                    })
                    .onKicked(reason -> runtime.offline());
            collaboration.start();
            if (!readyLatch.await(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Collaboration connection was not ready before timeout");
            }
            notifyHooks(hook -> hook.onReady(this));
        } catch (Exception e) {
            notifyHooks(hook -> hook.onError("start", e));
            CollaborationPlugin current = collaboration;
            if (current != null) {
                current.stop();
            }
            runtime.offline();
            throw e;
        }
    }

    public void pause() {
        runtime.pause();
    }

    public void resume() {
        runtime.resume();
    }

    public boolean drain(Duration timeout) throws InterruptedException {
        return runtime.drain(timeout);
    }

    public void stop() throws InterruptedException {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            CollaborationPlugin current = collaboration;
            if (current != null) {
                current.stop();
            }
            if (!runtime.drain(drainTimeout)) {
                throw new IllegalStateException("Digital worker did not drain before timeout");
            }
        } finally {
            runtime.offline();
            if (agentSessionTaskHandler != null) {
                agentSessionTaskHandler.close();
            }
            notifyHooks(WorkerHook::onStop);
        }
    }

    public void await() throws InterruptedException {
        runtime.await();
    }

    public WorkerState getState() {
        return runtime.state();
    }

    public int getActiveTaskCount() {
        return runtime.activeTasks();
    }

    public TaskExecutionState getTaskState(CollaborationPlugin.TaskDispatch task) {
        return taskExecutor.stateOf(task);
    }

    public MemoryClient memories() {
        return new MemoryClient(restClient);
    }

    public FileClient files() {
        return new FileClient(restClient);
    }

    public FileClient.UploadedFile uploadArtifact(String flowId, String sessionId, Path file) throws Exception {
        return files().upload(flowId, sessionId, file);
    }

    public String getInstanceId() {
        return instanceId;
    }

    public boolean isConnected() {
        CollaborationPlugin current = collaboration;
        return current != null && current.isConnected();
    }

    public RecoveryPolicy getRecoveryPolicy() {
        return recoveryPolicy;
    }

    public PermissionPolicy getPermissionPolicy() {
        return permissionPolicy;
    }

    public ApprovalChannel getApprovalChannel() {
        return approvalChannel;
    }

    private TaiyiAgent newAgent(String flowId) {
        return TaiyiAgent.builder()
                .baseUrl(baseUrl)
                .accessToken(auth::accessToken)
                .flowId(flowId)
                .clientId(clientId)
                .deviceId(deviceId)
                .requestTimeout(requestTimeout)
                .recoveryPolicy(recoveryPolicy)
                .permissionPolicy(permissionPolicy)
                .permissionHook(approvalHook)
                .build();
    }

    private static boolean isAgentSessionTask(CollaborationPlugin.TaskDispatch task) {
        return "remote_task".equals(task.taskKind)
                || "dialogue_subtask".equals(task.taskKind)
                || "schedule".equals(task.taskType);
    }

    private boolean dispatch(CollaborationPlugin.TaskDispatch task) {
        return runtime.submit(() -> {
            notifyHooks(hook -> hook.onTaskStart(task));
            TaskExecutionState state;
            try {
                if ("primary".equals(task.taskType)) {
                    taskHandler.execute(task, progress -> { });
                    state = TaskExecutionState.SUCCESS;
                } else {
                    state = taskExecutor.execute(task);
                }
            } catch (Exception exception) {
                state = TaskExecutionState.FAILED;
                notifyHooks(hook -> hook.onError("task", exception));
                log.error("Worker task execution failed: {}", task.taskId, exception);
            }
            TaskExecutionState finalState = state;
            notifyHooks(hook -> hook.onTaskFinish(task, finalState));
        });
    }

    private void notifyHooks(Consumer<WorkerHook> action) {
        for (WorkerHook hook : workerHooks) {
            try {
                action.accept(hook);
            } catch (RuntimeException exception) {
                log.warn("WorkerHook failed", exception);
            }
        }
    }

    public static final class Builder {
        private String baseUrl;
        private WorkerAuth auth;
        private WorkerIdentity identity;
        private WorkerRole role = WorkerRole.COLLABORATION_WORKER;
        private Set<Ability> abilities = Set.of();
        private Path workspace;
        private int concurrency = 3;
        private RecoveryPolicy recoveryPolicy = RecoveryPolicy.AUTO;
        private PermissionPolicy permissionPolicy = PermissionPolicy.CONFIRM;
        private ApprovalChannel approvalChannel = ApprovalChannel.PLATFORM;
        private PermissionHook approvalHandler;
        private ApprovalAudit approvalAudit;
        private final List<WorkerHook> workerHooks = new java.util.ArrayList<>();
        private WorkerTaskHandler taskHandler;
        private TaskReporter taskReporter;
        private WorkerIdentityValidator identityValidator;
        private String clientId;
        private String deviceId;
        private Duration connectTimeout = Duration.ofSeconds(15);
        private Duration drainTimeout = Duration.ofSeconds(30);
        private Duration requestTimeout = Duration.ofSeconds(30);

        public Builder baseUrl(String value) {
            this.baseUrl = value;
            return this;
        }

        public Builder auth(WorkerAuth value) {
            this.auth = value;
            return this;
        }

        public Builder identity(WorkerIdentity value) {
            this.identity = value;
            return this;
        }

        public Builder role(WorkerRole value) {
            this.role = value;
            return this;
        }

        public Builder abilities(Ability... values) {
            this.abilities = values == null ? Set.of() : Set.of(values);
            return this;
        }

        public Builder workspace(Path value) {
            this.workspace = value;
            return this;
        }

        public Builder concurrency(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("concurrency must be positive");
            }
            this.concurrency = value;
            return this;
        }

        public Builder recoveryPolicy(RecoveryPolicy value) {
            this.recoveryPolicy = value;
            return this;
        }

        public Builder permissionPolicy(PermissionPolicy value) {
            this.permissionPolicy = value;
            return this;
        }

        public Builder approvalChannel(ApprovalChannel value) {
            this.approvalChannel = value;
            return this;
        }

        public Builder approvalHandler(PermissionHook value) {
            this.approvalHandler = value;
            return this;
        }

        public Builder approvalAudit(ApprovalAudit value) {
            this.approvalAudit = value;
            return this;
        }

        public Builder onWorker(WorkerHook value) {
            if (value != null) {
                this.workerHooks.add(value);
            }
            return this;
        }

        public Builder onTask(WorkerTaskHandler value) {
            this.taskHandler = value;
            return this;
        }

        public Builder taskReporter(TaskReporter value) {
            this.taskReporter = value;
            return this;
        }

        public Builder identityValidator(WorkerIdentityValidator value) {
            this.identityValidator = value;
            return this;
        }

        public Builder clientId(String value) {
            this.clientId = value;
            return this;
        }

        public Builder deviceId(String value) {
            this.deviceId = value;
            return this;
        }

        public Builder connectTimeout(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("connectTimeout must be positive");
            }
            this.connectTimeout = value;
            return this;
        }

        public Builder drainTimeout(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("drainTimeout must be positive");
            }
            this.drainTimeout = value;
            return this;
        }

        public Builder requestTimeout(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("requestTimeout must be positive");
            }
            this.requestTimeout = value;
            return this;
        }

        public DigitalWorker build() {
            if (baseUrl == null || baseUrl.isBlank() || auth == null || identity == null || workspace == null) {
                throw new IllegalStateException("baseUrl, auth, identity and workspace are required");
            }
            if (role == null || recoveryPolicy == null || permissionPolicy == null || approvalChannel == null) {
                throw new IllegalStateException("Worker role and policies must not be null");
            }
            if (identityValidator == null) {
                throw new IllegalStateException(
                        "Platform identityValidator is required; SDK never self-registers a workerId");
            }
            return new DigitalWorker(this);
        }
    }
}
