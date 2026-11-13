package io.taiyiflow.sdk.core.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Bounded FIFO tool scheduler with global and per-session admission limits.
 *
 * <p>Only waiting executions consume queue capacity. The oldest eligible waiter is selected;
 * waiters from a session already at its own limit are skipped so they cannot block other sessions.
 * No dispatcher thread is created. Execution uses daemon virtual threads and one lazily-created
 * daemon timeout thread.</p>
 */
public final class ToolExecutionScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionScheduler.class);

    private final int globalConcurrency;
    private final int perSessionConcurrency;
    private final int queueCapacity;
    private final Duration executionTimeout;
    private final long executionTimeoutNanos;
    private final Object lock = new Object();
    private final ArrayDeque<ExecutionTask<?>> queue = new ArrayDeque<>();
    private final Set<ExecutionTask<?>> runningTasks =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Integer> runningBySession = new HashMap<>();
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("taiyiflow-tool-", 0).factory());
    private final ScheduledExecutorService timeoutExecutor =
            Executors.newSingleThreadScheduledExecutor(new TimeoutThreadFactory());

    private int running;
    private volatile boolean closed;

    public ToolExecutionScheduler(int globalConcurrency,
                                  int perSessionConcurrency,
                                  int queueCapacity,
                                  Duration executionTimeout) {
        if (globalConcurrency <= 0 || perSessionConcurrency <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "Tool concurrency limits and queue capacity must be positive");
        }
        if (executionTimeout == null || executionTimeout.isZero() || executionTimeout.isNegative()) {
            throw new IllegalArgumentException("Tool execution timeout must be positive");
        }
        try {
            this.executionTimeoutNanos = executionTimeout.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Tool execution timeout is too large", e);
        }
        if (executionTimeoutNanos <= 0) {
            throw new IllegalArgumentException("Tool execution timeout is too small");
        }
        this.globalConcurrency = globalConcurrency;
        this.perSessionConcurrency = perSessionConcurrency;
        this.queueCapacity = queueCapacity;
        this.executionTimeout = executionTimeout;
    }

    public <T> CompletableFuture<T> submit(ToolExecutionContext context, Callable<T> execution) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(execution, "execution");
        ExecutionTask<T> task = new ExecutionTask<>(context, execution);
        task.future.scheduler = this;
        task.future.task = task;

        if (context.getCancellation().isCancellationRequested()) {
            task.state = TaskState.DONE;
            task.future.cancelInternal();
            return task.future;
        }

        task.cancellationRegistration = context.getCancellation().onCancel(() -> cancel(task, true));
        task.future.whenComplete((ignored, failure) -> task.closeCancellationRegistration());
        if (task.future.isDone()) {
            return task.future;
        }

        boolean launch = false;
        boolean drain = false;
        synchronized (lock) {
            if (task.future.isDone()) {
                return task.future;
            }
            if (closed) {
                task.state = TaskState.DONE;
                task.future.completeExceptionally(closedException());
            } else if (queue.isEmpty() && canRunLocked(task.context.getSessionId())) {
                markRunningLocked(task);
                launch = true;
            } else if (queue.size() >= queueCapacity) {
                task.state = TaskState.DONE;
                ToolExecutionException error = new ToolExecutionException(
                        ToolExecutionException.Reason.QUEUE_FULL,
                        "Tool execution queue is full (capacity " + queueCapacity + ")");
                task.future.completeExceptionally(error);
                log.warn("Rejected tool execution for session {}: {}", task.context.getSessionId(),
                        error.getMessage());
            } else {
                task.state = TaskState.QUEUED;
                queue.addLast(task);
                drain = true;
                log.debug("Queued tool execution for session {}; queued={}, running={}",
                        task.context.getSessionId(), queue.size(), running);
            }
        }
        if (launch) {
            launch(task);
        } else if (drain) {
            drainQueue();
        }
        return task.future;
    }

    public <T> T execute(ToolExecutionContext context, Callable<T> execution)
            throws ToolExecutionException {
        CompletableFuture<T> future = submit(context, execution);
        try {
            return future.get();
        } catch (CancellationException e) {
            throw new ToolExecutionException(ToolExecutionException.Reason.CANCELLED,
                    "Tool execution cancelled", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ToolExecutionException(ToolExecutionException.Reason.CANCELLED,
                    "Tool execution interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ToolExecutionException) {
                throw (ToolExecutionException) cause;
            }
            String detail = cause == null || cause.getMessage() == null
                    ? "unknown failure" : cause.getMessage();
            throw new ToolExecutionException(ToolExecutionException.Reason.EXECUTION_FAILED,
                    "Tool execution failed: " + detail, cause);
        }
    }

    private <T> void launch(ExecutionTask<T> task) {
        try {
            executor.execute(() -> run(task));
        } catch (RejectedExecutionException e) {
            ToolExecutionException error = closed ? closedException()
                    : new ToolExecutionException(ToolExecutionException.Reason.EXECUTION_FAILED,
                            "Tool execution could not be started", e);
            task.future.completeExceptionally(error);
            finishRunning(task);
        }
    }

    private <T> void run(ExecutionTask<T> task) {
        task.worker = Thread.currentThread();
        if (task.future.isCancelled() || task.context.getCancellation().isCancellationRequested()) {
            Thread.currentThread().interrupt();
        }
        ScheduledFuture<?> timeout = null;
        T result = null;
        Throwable failure = null;
        boolean succeeded = false;
        try {
            if (task.future.isDone()) {
                return;
            }
            try {
                timeout = timeoutExecutor.schedule(() -> timeout(task), executionTimeoutNanos,
                        TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException e) {
                task.future.completeExceptionally(closedException());
                return;
            }
            if (task.future.isDone() || task.context.getCancellation().isCancellationRequested()) {
                task.future.cancelInternal();
                return;
            }
            try {
                result = task.execution.call();
                succeeded = true;
            } catch (Throwable executionFailure) {
                failure = executionFailure;
            }
        } finally {
            if (timeout != null) {
                timeout.cancel(false);
            }
            task.worker = null;
            finishRunning(task);
            // Normal completion becomes visible only after this execution has released its permit.
            if (succeeded) {
                task.future.complete(result);
            } else if (failure != null && !task.future.isDone()) {
                if (task.context.getCancellation().isCancellationRequested()
                        || failure instanceof InterruptedException) {
                    task.future.cancelInternal();
                } else {
                    task.future.completeExceptionally(failure);
                }
            }
        }
    }

    private void timeout(ExecutionTask<?> task) {
        ToolExecutionException error = new ToolExecutionException(
                ToolExecutionException.Reason.EXECUTION_TIMEOUT,
                "Tool execution timed out after " + executionTimeout.toMillis() + " ms");
        if (task.future.completeExceptionally(error)) {
            log.warn("Tool execution timed out for session {} after {} ms; queued={}, running={}",
                    task.context.getSessionId(), executionTimeout.toMillis(), getQueuedCount(),
                    getRunningCount());
            Thread worker = task.worker;
            if (worker != null) {
                worker.interrupt();
            }
        }
    }

    private boolean cancel(ExecutionTask<?> task, boolean interruptRunning) {
        Thread worker = null;
        synchronized (lock) {
            if (task.state == TaskState.DONE) {
                return false;
            }
            if (task.state == TaskState.QUEUED) {
                queue.remove(task);
                task.state = TaskState.DONE;
                log.debug("Removed cancelled tool execution for session {}; queued={}",
                        task.context.getSessionId(), queue.size());
            } else if (task.state == TaskState.RUNNING) {
                worker = task.worker;
            } else {
                task.state = TaskState.DONE;
            }
        }
        boolean cancelled = task.future.cancelInternal();
        if (interruptRunning && worker != null) {
            worker.interrupt();
        }
        return cancelled;
    }

    private void finishRunning(ExecutionTask<?> task) {
        synchronized (lock) {
            if (task.state != TaskState.RUNNING) {
                return;
            }
            task.state = TaskState.DONE;
            runningTasks.remove(task);
            running--;
            String sessionId = task.context.getSessionId();
            Integer count = runningBySession.get(sessionId);
            if (count != null) {
                if (count <= 1) {
                    runningBySession.remove(sessionId);
                } else {
                    runningBySession.put(sessionId, count - 1);
                }
            }
            log.debug("Finished tool execution for session {}; queued={}, running={}",
                    sessionId, queue.size(), running);
        }
        drainQueue();
    }

    private void drainQueue() {
        while (true) {
            ExecutionTask<?> task;
            synchronized (lock) {
                if (closed || running >= globalConcurrency) {
                    return;
                }
                task = pollFirstEligibleLocked();
                if (task == null) {
                    return;
                }
                markRunningLocked(task);
            }
            launch(task);
        }
    }

    private ExecutionTask<?> pollFirstEligibleLocked() {
        Iterator<ExecutionTask<?>> iterator = queue.iterator();
        while (iterator.hasNext()) {
            ExecutionTask<?> candidate = iterator.next();
            if (sessionRunningLocked(candidate.context.getSessionId()) < perSessionConcurrency) {
                iterator.remove();
                return candidate;
            }
        }
        return null;
    }

    private boolean canRunLocked(String sessionId) {
        return running < globalConcurrency && sessionRunningLocked(sessionId) < perSessionConcurrency;
    }

    private int sessionRunningLocked(String sessionId) {
        return runningBySession.getOrDefault(sessionId, 0);
    }

    private void markRunningLocked(ExecutionTask<?> task) {
        task.state = TaskState.RUNNING;
        runningTasks.add(task);
        running++;
        runningBySession.merge(task.context.getSessionId(), 1, Integer::sum);
    }

    public int getRunningCount() {
        synchronized (lock) {
            return running;
        }
    }

    public int getQueuedCount() {
        synchronized (lock) {
            return queue.size();
        }
    }

    public int getSessionRunningCount(String sessionId) {
        synchronized (lock) {
            return sessionRunningLocked(sessionId);
        }
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        List<ExecutionTask<?>> queued;
        List<ExecutionTask<?>> active;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            queued = new ArrayList<>(queue);
            queue.clear();
            queued.forEach(task -> task.state = TaskState.DONE);
            active = new ArrayList<>(runningTasks);
        }
        ToolExecutionException error = closedException();
        queued.forEach(task -> task.future.completeExceptionally(error));
        active.forEach(task -> {
            task.future.completeExceptionally(error);
            Thread worker = task.worker;
            if (worker != null) {
                worker.interrupt();
            }
        });
        timeoutExecutor.shutdownNow();
        executor.shutdownNow();
        try {
            if (!timeoutExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                log.warn("Tool timeout executor did not stop within one second");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.debug("Closed tool execution scheduler; cancelledQueued={}, interruptedRunning={}",
                queued.size(), active.size());
    }

    private static ToolExecutionException closedException() {
        return new ToolExecutionException(ToolExecutionException.Reason.CLOSED,
                "Tool runtime is closed");
    }

    private enum TaskState {
        NEW,
        QUEUED,
        RUNNING,
        DONE
    }

    private static final class ExecutionTask<T> {
        private final ToolExecutionContext context;
        private final Callable<T> execution;
        private final TaskFuture<T> future = new TaskFuture<>();
        private volatile Thread worker;
        private volatile ToolExecutionContext.CancellationRegistration cancellationRegistration;
        private TaskState state = TaskState.NEW;

        private ExecutionTask(ToolExecutionContext context, Callable<T> execution) {
            this.context = context;
            this.execution = execution;
        }

        private void closeCancellationRegistration() {
            ToolExecutionContext.CancellationRegistration registration = cancellationRegistration;
            if (registration != null) {
                registration.close();
            }
        }
    }

    private static final class TaskFuture<T> extends CompletableFuture<T> {
        private ToolExecutionScheduler scheduler;
        private ExecutionTask<T> task;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            ToolExecutionScheduler currentScheduler = scheduler;
            ExecutionTask<T> currentTask = task;
            if (currentScheduler == null || currentTask == null) {
                return super.cancel(mayInterruptIfRunning);
            }
            return currentScheduler.cancel(currentTask, mayInterruptIfRunning);
        }

        private boolean cancelInternal() {
            return super.cancel(true);
        }
    }

    private static final class TimeoutThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "taiyiflow-tool-timeouts");
            thread.setDaemon(true);
            return thread;
        }
    }
}
