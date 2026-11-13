package io.taiyiflow.sdk.worker;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class WorkerRuntime {
    private final Object monitor = new Object();
    private final ExecutorService taskExecutor;
    private final CountDownLatch offlineLatch = new CountDownLatch(1);
    private WorkerState state = WorkerState.OFFLINE;
    private boolean acceptingTasks;
    private boolean terminated;
    private int activeTasks;

    WorkerRuntime(int concurrency) {
        this(concurrency, 128);
    }

    WorkerRuntime(int concurrency, int queueCapacity) {
        if (concurrency <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("Worker concurrency and queue capacity must be positive");
        }
        this.taskExecutor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), Thread.ofVirtual().factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    void online() {
        synchronized (monitor) {
            if (!terminated) {
                acceptingTasks = true;
                state = activeTasks == 0 ? WorkerState.ONLINE : WorkerState.BUSY;
            }
        }
    }

    void pause() {
        synchronized (monitor) {
            if (!terminated) {
                acceptingTasks = false;
                state = WorkerState.PAUSED;
            }
        }
    }

    void resume() {
        online();
    }

    boolean submit(Runnable task) {
        return submitWithHandle(task).accepted();
    }

    Submission submitWithHandle(Runnable task) {
        synchronized (monitor) {
            if (!acceptingTasks || terminated) {
                return Submission.rejected();
            }
            activeTasks++;
            state = WorkerState.BUSY;
        }
        TrackedTask tracked = new TrackedTask(task);
        try {
            taskExecutor.execute(tracked);
            return new Submission(tracked, true);
        } catch (RuntimeException e) {
            tracked.cancel(false);
            return new Submission(tracked, false);
        }
    }

    boolean drain(Duration timeout) throws InterruptedException {
        pause();
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (activeTasks > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            }
            return true;
        }
    }

    void offline() {
        synchronized (monitor) {
            if (terminated) {
                return;
            }
            terminated = true;
            acceptingTasks = false;
            state = WorkerState.OFFLINE;
            List<Runnable> discardedTasks = taskExecutor.shutdownNow();
            discardedTasks.forEach(task -> ((TrackedTask) task).cancel(false));
            monitor.notifyAll();
        }
        offlineLatch.countDown();
    }

    void await() throws InterruptedException {
        offlineLatch.await();
    }

    WorkerState state() {
        synchronized (monitor) {
            return state;
        }
    }

    int activeTasks() {
        synchronized (monitor) {
            return activeTasks;
        }
    }

    record Submission(Future<?> future, boolean accepted) {
        private static Submission rejected() {
            return new Submission(CompletableFuture.failedFuture(
                    new RejectedExecutionException("Worker is not accepting tasks")), false);
        }
    }

    private final class TrackedTask extends FutureTask<Void> {
        private final AtomicBoolean completionSignaled = new AtomicBoolean();
        private volatile boolean started;

        private TrackedTask(Runnable task) {
            super(task, null);
        }

        @Override
        public void run() {
            started = true;
            try {
                super.run();
            } finally {
                completeOnce();
            }
        }

        @Override
        protected void done() {
            if (!started) {
                completeOnce();
            }
        }

        private void completeOnce() {
            if (completionSignaled.compareAndSet(false, true)) {
                completeTask();
            }
        }
    }

    private void completeTask() {
        synchronized (monitor) {
            activeTasks--;
            if (!terminated) {
                state = acceptingTasks ? WorkerState.ONLINE : WorkerState.PAUSED;
            }
            monitor.notifyAll();
        }
    }
}
