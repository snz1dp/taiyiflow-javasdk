package io.taiyiflow.sdk.core.dispatch;

import io.taiyiflow.sdk.core.subtask.SubtaskCapabilitySnapshot;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Immutable, explicit context for one tool execution.
 *
 * <p>The cancellation object is a shared control handle; the context itself never reads mutable
 * session or worker state and does not rely on a {@link ThreadLocal}.</p>
 */
public final class ToolExecutionContext {

    public static final String REQUEST_MODE_STANDALONE = "standalone";

    private final String flowId;
    private final String sessionId;
    private final String runId;
    private final String taskId;
    private final String messageId;
    private final Path workspace;
    private final String requestMode;
    private final Set<String> allowedTools;
    private SubtaskCapabilitySnapshot capabilities;
    private final Cancellation cancellation;

    public ToolExecutionContext(String flowId,
                                String sessionId,
                                String runId,
                                String taskId,
                                Path workspace,
                                String requestMode,
                                Cancellation cancellation) {
        this(flowId, sessionId, runId, taskId, null, workspace, requestMode, null, cancellation);
    }

    public ToolExecutionContext(String flowId,
                                String sessionId,
                                String runId,
                                String taskId,
                                String messageId,
                                Path workspace,
                                String requestMode,
                                Set<String> allowedTools,
                                Cancellation cancellation) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        this.flowId = flowId;
        this.sessionId = sessionId;
        this.runId = runId;
        this.taskId = taskId;
        this.messageId = messageId;
        this.workspace = workspace == null ? null : workspace.toAbsolutePath().normalize();
        this.requestMode = requestMode == null ? REQUEST_MODE_STANDALONE : requestMode;
        this.allowedTools = allowedTools == null ? null : Set.copyOf(allowedTools);
        this.capabilities = new SubtaskCapabilitySnapshot(null, null, null, null,
                List.of(), List.of(), this.allowedTools);
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    /** Creates a minimal context used by the legacy standalone dispatch API. */
    public static ToolExecutionContext standalone(String sessionId) {
        return new ToolExecutionContext(null, sessionId, null, null, null,
                REQUEST_MODE_STANDALONE, Cancellation.none());
    }

    public static Builder builder(String flowId, String sessionId) {
        return new Builder(flowId, sessionId);
    }

    public String getFlowId() {
        return flowId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getMessageId() {
        return messageId;
    }

    public Path getWorkspace() {
        return workspace;
    }

    public String getRequestMode() {
        return requestMode;
    }

    /** Null means unrestricted; an empty set explicitly denies every client tool. */
    public Set<String> getAllowedTools() {
        return allowedTools;
    }

    public SubtaskCapabilitySnapshot getCapabilities() {
        return capabilities;
    }

    public Cancellation getCancellation() {
        return cancellation;
    }

    public static final class Builder {
        private final String flowId;
        private final String sessionId;
        private String runId;
        private String taskId;
        private String messageId;
        private Path workspace;
        private String requestMode = REQUEST_MODE_STANDALONE;
        private Set<String> allowedTools;
        private SubtaskCapabilitySnapshot capabilities;
        private Cancellation cancellation = Cancellation.none();

        private Builder(String flowId, String sessionId) {
            if (flowId == null || flowId.isBlank()) {
                throw new IllegalArgumentException("flowId is required");
            }
            if (sessionId == null || sessionId.isBlank()) {
                throw new IllegalArgumentException("sessionId is required");
            }
            this.flowId = flowId;
            this.sessionId = sessionId;
        }

        public Builder runId(String value) {
            this.runId = value;
            return this;
        }

        public Builder taskId(String value) {
            this.taskId = value;
            return this;
        }

        public Builder messageId(String value) {
            this.messageId = value;
            return this;
        }

        public Builder workspace(Path value) {
            this.workspace = value;
            return this;
        }

        public Builder requestMode(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("requestMode must not be blank");
            }
            this.requestMode = value;
            return this;
        }

        public Builder allowedTools(Set<String> value) {
            this.allowedTools = value == null ? null : Set.copyOf(value);
            return this;
        }

        public Builder capabilities(SubtaskCapabilitySnapshot value) {
            this.capabilities = value;
            this.allowedTools = value == null ? null : value.getAllowedTools();
            return this;
        }

        public Builder cancellation(Cancellation value) {
            this.cancellation = Objects.requireNonNull(value, "cancellation");
            return this;
        }

        public ToolExecutionContext build() {
            ToolExecutionContext context = new ToolExecutionContext(flowId, sessionId, runId, taskId,
                    messageId, workspace, requestMode, allowedTools, cancellation);
            if (capabilities != null) {
                context.capabilities = capabilities;
            }
            return context;
        }
    }

    /** Read-only cancellation view carried by an execution context. */
    public interface Cancellation {
        boolean isCancellationRequested();

        CancellationRegistration onCancel(Runnable callback);

        static Cancellation none() {
            return NoneCancellation.INSTANCE;
        }
    }

    @FunctionalInterface
    public interface CancellationRegistration {
        void close();
    }

    /** Mutable cancellation source that can be shared with a later task-control implementation. */
    public static final class CancellationSource implements Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Object lock = new Object();
        private final Map<Long, Runnable> callbacks = new HashMap<>();
        private long nextCallbackId;

        public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return false;
            }
            Map<Long, Runnable> pending;
            synchronized (lock) {
                pending = new HashMap<>(callbacks);
                callbacks.clear();
            }
            pending.values().forEach(Runnable::run);
            return true;
        }

        @Override
        public boolean isCancellationRequested() {
            return cancelled.get();
        }

        @Override
        public CancellationRegistration onCancel(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            final long callbackId;
            synchronized (lock) {
                if (!cancelled.get()) {
                    callbackId = ++nextCallbackId;
                    callbacks.put(callbackId, callback);
                    return () -> {
                        synchronized (lock) {
                            callbacks.remove(callbackId);
                        }
                    };
                }
            }
            callback.run();
            return () -> { };
        }
    }

    private static final class NoneCancellation implements Cancellation {
        private static final NoneCancellation INSTANCE = new NoneCancellation();

        @Override
        public boolean isCancellationRequested() {
            return false;
        }

        @Override
        public CancellationRegistration onCancel(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            return () -> { };
        }
    }
}
