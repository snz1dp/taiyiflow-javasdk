package io.taiyiflow.sdk.plugins.shell;

import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.process.ManagedProcess;
import io.taiyiflow.sdk.plugins.workspace.WorkspaceAudit;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class BackgroundProcessRegistry implements AutoCloseable {
    private final int maxProcesses;
    private final int outputLimitChars;
    private final Duration stopTimeout;
    private final Map<String, Entry> processes = new ConcurrentHashMap<>();
    private final Object capacityLock = new Object();

    BackgroundProcessRegistry(int maxProcesses, int outputLimitChars, Duration stopTimeout) {
        if (maxProcesses <= 0 || outputLimitChars <= 0 || stopTimeout.isNegative()) {
            throw new IllegalArgumentException("Invalid background process limits");
        }
        this.maxProcesses = maxProcesses;
        this.outputLimitChars = outputLimitChars;
        this.stopTimeout = stopTimeout;
    }

    Entry start(Process process, List<String> command, Path cwd, ToolExecutionContext context,
                WorkspaceAudit audit, WorkspaceAudit.Scan baseline) {
        synchronized (capacityLock) {
            evictTerminal();
            if (processes.size() >= maxProcesses) {
                process.destroyForcibly();
                throw new IllegalStateException("Background process registry is full");
            }
            String handle = "proc_" + UUID.randomUUID();
            Entry entry = new Entry(handle, command, cwd, context, audit, baseline);
            entry.process = new ManagedProcess(process, outputLimitChars, ignored -> onExit(entry));
            entry.cancellationRegistration = context.getCancellation().onCancel(() -> stopEntry(entry));
            if (!process.isAlive()) {
                entry.cancellationRegistration.close();
            }
            processes.put(handle, entry);
            return entry;
        }
    }

    List<Entry> list(String sessionId) {
        return processes.values().stream()
                .filter(entry -> entry.sessionId.equals(sessionId))
                .sorted(Comparator.comparing(entry -> entry.createdAt))
                .toList();
    }

    Entry find(String handle, String sessionId) {
        if (handle == null || handle.isBlank()) {
            return null;
        }
        Entry entry = processes.get(handle);
        return entry == null || !entry.sessionId.equals(sessionId) ? null : entry;
    }

    Entry stop(String handle, String sessionId) {
        Entry entry = find(handle, sessionId);
        if (entry != null) {
            stopEntry(entry);
        }
        return entry;
    }

    boolean cleanup(String handle, String sessionId) {
        Entry entry = find(handle, sessionId);
        if (entry == null || entry.process.isAlive() || !processes.remove(handle, entry)) {
            return false;
        }
        entry.cancellationRegistration.close();
        return true;
    }

    int cleanupFinished(String sessionId) {
        int removed = 0;
        for (Entry entry : list(sessionId)) {
            if (!entry.process.isAlive() && processes.remove(entry.handle, entry)) {
                entry.cancellationRegistration.close();
                removed++;
            }
        }
        return removed;
    }

    void stopSession(String sessionId) {
        list(sessionId).forEach(this::stopEntry);
    }

    @Override
    public void close() {
        processes.values().forEach(entry -> {
            stopEntry(entry);
            entry.cancellationRegistration.close();
        });
        processes.clear();
    }

    int size() {
        return processes.size();
    }

    private void onExit(Entry entry) {
        try {
            if (entry.baseline != null) {
                entry.auditResult = entry.audit.diffAndRefresh(
                        entry.sessionId, entry.runId, entry.baseline, java.util.Set.of());
            }
        } finally {
            ToolExecutionContext.CancellationRegistration registration = entry.cancellationRegistration;
            if (registration != null) {
                registration.close();
            }
        }
    }

    private void stopEntry(Entry entry) {
        entry.process.stop(stopTimeout);
    }

    private void evictTerminal() {
        processes.values().stream()
                .filter(entry -> !entry.process.isAlive())
                .min(Comparator.comparing(entry -> entry.createdAt))
                .ifPresent(entry -> {
                    if (processes.remove(entry.handle, entry)) {
                        entry.cancellationRegistration.close();
                    }
                });
    }

    static final class Entry {
        private final String handle;
        private final List<String> command;
        private final Path cwd;
        private final String sessionId;
        private final String runId;
        private final String taskId;
        private final Instant createdAt = Instant.now();
        private final WorkspaceAudit audit;
        private final WorkspaceAudit.Scan baseline;
        private volatile ManagedProcess process;
        private volatile WorkspaceAudit.AuditResult auditResult;
        private volatile ToolExecutionContext.CancellationRegistration cancellationRegistration;

        private Entry(String handle, List<String> command, Path cwd, ToolExecutionContext context,
                      WorkspaceAudit audit, WorkspaceAudit.Scan baseline) {
            this.handle = handle;
            this.command = List.copyOf(command);
            this.cwd = cwd;
            this.sessionId = context.getSessionId();
            this.runId = context.getRunId();
            this.taskId = context.getTaskId();
            this.audit = audit;
            this.baseline = baseline;
        }

        String handle() {
            return handle;
        }

        List<String> command() {
            return command;
        }

        Path cwd() {
            return cwd;
        }

        String sessionId() {
            return sessionId;
        }

        String runId() {
            return runId;
        }

        String taskId() {
            return taskId;
        }

        Instant createdAt() {
            return createdAt;
        }

        ManagedProcess process() {
            return process;
        }

        WorkspaceAudit.AuditResult auditResult() {
            return auditResult;
        }

        String status() {
            if (process.isAlive()) {
                return process.isStopRequested() ? "stopping" : "running";
            }
            return process.isStopRequested() ? "stopped" : "exited";
        }
    }
}
