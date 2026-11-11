package io.taiyiflow.sdk.worker;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class WorkerRuntime {
    private final Object monitor = new Object();
    private final ExecutorService taskExecutor;
    private final CountDownLatch offlineLatch = new CountDownLatch(1);
    private WorkerState state = WorkerState.OFFLINE;
    private boolean acceptingTasks;
    private boolean terminated;
    private int activeTasks;

    WorkerRuntime(int concurrency) {
        this.taskExecutor = Executors.newFixedThreadPool(concurrency, Thread.ofVirtual().factory());
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
        synchronized (monitor) {
            if (!acceptingTasks || terminated) {
                return false;
            }
            activeTasks++;
            state = WorkerState.BUSY;
        }
        try {
            taskExecutor.execute(() -> {
                try {
                    task.run();
                } finally {
                    completeTask();
                }
            });
            return true;
        } catch (RuntimeException e) {
            completeTask();
            return false;
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
            activeTasks -= discardedTasks.size();
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
