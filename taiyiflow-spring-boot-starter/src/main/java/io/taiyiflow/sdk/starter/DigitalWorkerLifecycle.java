package io.taiyiflow.sdk.starter;

import io.taiyiflow.sdk.worker.DigitalWorker;
import org.springframework.context.SmartLifecycle;

final class DigitalWorkerLifecycle implements SmartLifecycle {
    private final DigitalWorker worker;
    private volatile boolean running;

    DigitalWorkerLifecycle(DigitalWorker worker) {
        this.worker = worker;
    }

    @Override
    public void start() {
        try {
            worker.start();
            running = true;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to start digital worker", exception);
        }
    }

    @Override
    public void stop() {
        try {
            worker.stop();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
