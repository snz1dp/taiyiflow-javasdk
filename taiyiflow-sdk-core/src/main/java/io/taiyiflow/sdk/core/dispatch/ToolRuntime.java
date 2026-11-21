package io.taiyiflow.sdk.core.dispatch;

import io.taiyiflow.sdk.core.report.InMemoryReportOutbox;
import io.taiyiflow.sdk.core.report.ReportAlert;
import io.taiyiflow.sdk.core.report.ReportOutbox;
import io.taiyiflow.sdk.transport.rest.RestClient;
import io.taiyiflow.sdk.transport.rest.ToolResultReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Worker-shareable tool execution resources. Inject one instance into multiple agents to enforce
 * a common global limit; a standalone agent creates and owns one from {@link Config#defaults()}.
 */
public final class ToolRuntime implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ToolRuntime.class);

    private final Config config;
    private final ToolExecutionScheduler scheduler;
    private final ReportOutbox reportOutbox;
    private final Consumer<ReportAlert> alertHandler;
    private final Set<String> unsafeRecoverySessions = ConcurrentHashMap.newKeySet();

    public ToolRuntime() {
        this(Config.defaults());
    }

    public ToolRuntime(Config config) {
        this(config, new InMemoryReportOutbox(), ToolRuntime::logSevereAlert);
    }

    /** Worker entry point for injecting one shared persistent outbox into every flow agent. */
    public ToolRuntime(Config config, ReportOutbox reportOutbox, Consumer<ReportAlert> alertHandler) {
        this.config = Objects.requireNonNull(config, "config");
        this.reportOutbox = Objects.requireNonNull(reportOutbox, "reportOutbox");
        this.alertHandler = Objects.requireNonNull(alertHandler, "alertHandler");
        this.scheduler = new ToolExecutionScheduler(config.getGlobalConcurrency(),
                config.getPerSessionConcurrency(), config.getQueueCapacity(),
                config.getExecutionTimeout());
    }

    public <T> T execute(ToolExecutionContext context, Callable<T> execution)
            throws ToolExecutionException {
        return scheduler.execute(context, execution);
    }

    public <T> java.util.concurrent.CompletableFuture<T> submit(
            ToolExecutionContext context, Callable<T> execution) {
        return scheduler.submit(context, execution);
    }

    public Config getConfig() {
        return config;
    }

    public ToolExecutionScheduler getScheduler() {
        return scheduler;
    }

    public ReportOutbox getReportOutbox() {
        return reportOutbox;
    }

    public void flushClientResults(RestClient restClient) {
        ToolResultReporter reporter = new ToolResultReporter(restClient, this);
        reporter.flush();
        reporter.reconcileDeadLetters();
    }

    public void reportFailure(ReportAlert alert) {
        if (alert.sessionId() != null && !alert.sessionId().isBlank()) {
            unsafeRecoverySessions.add(alert.sessionId());
        }
        try {
            alertHandler.accept(alert);
        } catch (RuntimeException exception) {
            log.error("Report alert handler failed for session {}", alert.sessionId());
        }
    }

    public boolean isAutomaticRecoverySafe(String sessionId) {
        return !unsafeRecoverySessions.contains(sessionId);
    }

    public void clearRecoverySafety(String sessionId) {
        if (sessionId != null) {
            unsafeRecoverySessions.remove(sessionId);
        }
    }

    private static void logSevereAlert(ReportAlert alert) {
        log.error("SEVERE tool result report failure: type={}, flow={}, session={}, run={}, call={}, checkpoint={}",
                alert.reportType(), alert.flowId(), alert.sessionId(), alert.runId(),
                alert.callId(), alert.checkpointId());
    }

    public boolean isClosed() {
        return scheduler.isClosed();
    }

    @Override
    public void close() {
        scheduler.close();
    }

    /** Immutable bounded scheduler configuration. There is no unbounded fallback value. */
    public static final class Config {
        public static final int DEFAULT_GLOBAL_CONCURRENCY = 8;
        public static final int DEFAULT_PER_SESSION_CONCURRENCY = 2;
        public static final int DEFAULT_QUEUE_CAPACITY = 128;
        public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofSeconds(60);

        private final int globalConcurrency;
        private final int perSessionConcurrency;
        private final int queueCapacity;
        private final Duration executionTimeout;

        public Config(int globalConcurrency,
                      int perSessionConcurrency,
                      int queueCapacity,
                      Duration executionTimeout) {
            if (globalConcurrency <= 0) {
                throw new IllegalArgumentException("Global tool concurrency must be positive");
            }
            if (perSessionConcurrency <= 0) {
                throw new IllegalArgumentException("Per-session tool concurrency must be positive");
            }
            if (queueCapacity <= 0) {
                throw new IllegalArgumentException("Tool queue capacity must be positive");
            }
            validateTimeout(executionTimeout);
            this.globalConcurrency = globalConcurrency;
            this.perSessionConcurrency = perSessionConcurrency;
            this.queueCapacity = queueCapacity;
            this.executionTimeout = executionTimeout;
        }

        public static Config defaults() {
            return builder().build();
        }

        public static Builder builder() {
            return new Builder();
        }

        public int getGlobalConcurrency() {
            return globalConcurrency;
        }

        public int getPerSessionConcurrency() {
            return perSessionConcurrency;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public Duration getExecutionTimeout() {
            return executionTimeout;
        }

        private static void validateTimeout(Duration timeout) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("Tool execution timeout must be positive");
            }
            try {
                if (timeout.toNanos() <= 0) {
                    throw new IllegalArgumentException("Tool execution timeout is too small");
                }
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Tool execution timeout is too large", e);
            }
        }

        public static final class Builder {
            private int globalConcurrency = DEFAULT_GLOBAL_CONCURRENCY;
            private int perSessionConcurrency = DEFAULT_PER_SESSION_CONCURRENCY;
            private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
            private Duration executionTimeout = DEFAULT_EXECUTION_TIMEOUT;

            public Builder globalConcurrency(int value) {
                this.globalConcurrency = value;
                return this;
            }

            public Builder perSessionConcurrency(int value) {
                this.perSessionConcurrency = value;
                return this;
            }

            public Builder queueCapacity(int value) {
                this.queueCapacity = value;
                return this;
            }

            public Builder executionTimeout(Duration value) {
                this.executionTimeout = value;
                return this;
            }

            public Config build() {
                return new Config(globalConcurrency, perSessionConcurrency, queueCapacity,
                        executionTimeout);
            }
        }
    }
}
