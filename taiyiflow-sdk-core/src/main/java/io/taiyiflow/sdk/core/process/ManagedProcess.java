package io.taiyiflow.sdk.core.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class ManagedProcess implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedProcess.class);

    public enum OutputStream {
        STDOUT,
        STDERR
    }

    private final Process process;
    private final BoundedOutput stdout;
    private final BoundedOutput stderr;
    private final CountDownLatch exited = new CountDownLatch(1);
    private final Consumer<ManagedProcess> exitListener;
    private volatile boolean stopRequested;
    private volatile Integer exitCode;
    private volatile Instant endedAt;

    public ManagedProcess(Process process, int outputLimitChars, Consumer<ManagedProcess> exitListener) {
        this(process, outputLimitChars, exitListener, null);
    }

    public ManagedProcess(Process process, int outputLimitChars, Consumer<ManagedProcess> exitListener,
                          Consumer<String> stdoutConsumer) {
        if (outputLimitChars <= 0) {
            throw new IllegalArgumentException("Process output limit must be positive");
        }
        this.process = process;
        this.stdout = new BoundedOutput(outputLimitChars);
        this.stderr = new BoundedOutput(outputLimitChars);
        this.exitListener = exitListener == null ? ignored -> { } : exitListener;
        Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout, stdoutConsumer));
        Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr, null));
        Thread.ofVirtual().start(this::awaitExit);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    public Integer exitCode() {
        return exitCode;
    }

    public Instant endedAt() {
        return endedAt;
    }

    public OutputChunk output(OutputStream stream, long offset) {
        return (stream == OutputStream.STDERR ? stderr : stdout).read(offset);
    }

    public boolean await(Duration timeout) throws InterruptedException {
        return exited.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop(Duration gracefulTimeout) {
        stopRequested = true;
        if (!process.isAlive()) {
            return;
        }
        List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
        Collections.reverse(tree);
        tree.add(process.toHandle());
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
        awaitTree(tree, gracefulTimeout);
        tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        awaitTree(tree, Duration.ofMillis(200));
    }

    @Override
    public void close() {
        stop(Duration.ofSeconds(2));
    }

    private void awaitExit() {
        try {
            exitCode = process.waitFor();
            endedAt = Instant.now();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            stop(Duration.ofMillis(200));
        } finally {
            exited.countDown();
            try {
                exitListener.accept(this);
            } catch (RuntimeException exception) {
                log.warn("Managed process exit listener failed", exception);
            }
        }
    }

    private static void awaitTree(List<ProcessHandle> tree, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (tree.stream().anyMatch(ProcessHandle::isAlive)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void drain(InputStream input, BoundedOutput output, Consumer<String> consumer) {
        char[] buffer = new char[4096];
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (read > 0) {
                    output.append(buffer, read);
                    if (consumer != null) {
                        consumer.accept(new String(buffer, 0, read));
                    }
                }
            }
        } catch (IOException ignored) {
            // Process teardown closes the streams; already drained output remains available.
        }
    }

    public record OutputChunk(String data, long nextOffset, long droppedChars, boolean offsetReset) {
    }

    private static final class BoundedOutput {
        private final int limit;
        private final StringBuilder tail = new StringBuilder();
        private long totalChars;
        private long droppedChars;

        private BoundedOutput(int limit) {
            this.limit = limit;
        }

        private synchronized void append(char[] value, int count) {
            tail.append(value, 0, count);
            totalChars += count;
            int excess = tail.length() - limit;
            if (excess > 0) {
                tail.delete(0, excess);
                droppedChars += excess;
            }
        }

        private synchronized OutputChunk read(long requestedOffset) {
            long offset = Math.max(0, requestedOffset);
            boolean reset = offset < droppedChars;
            offset = Math.max(offset, droppedChars);
            offset = Math.min(offset, totalChars);
            int start = Math.toIntExact(offset - droppedChars);
            return new OutputChunk(tail.substring(start), totalChars, droppedChars, reset);
        }
    }
}
