package io.taiyiflow.sdk.core.dispatch;

import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.transport.envelope.ToolCall;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ApprovalBroker {

    public record Approval(PermissionHook.Decision decision, String reason) {
    }

    private final Map<String, CompletableFuture<Approval>> pending = new ConcurrentHashMap<>();

    public Approval await(ToolCall call, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<Approval> future = new CompletableFuture<>();
        if (pending.putIfAbsent(call.getId(), future) != null) {
            throw new IllegalStateException("Approval is already pending for tool call " + call.getId());
        }
        try {
            if (timeout.isZero()) {
                return future.get();
            }
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } finally {
            pending.remove(call.getId(), future);
        }
    }

    public boolean approve(String callId) {
        return complete(callId, new Approval(PermissionHook.Decision.ALLOW, null));
    }

    public boolean deny(String callId, String reason) {
        String message = reason == null || reason.isBlank() ? "Permission denied by user" : reason;
        return complete(callId, new Approval(PermissionHook.Decision.DENY, message));
    }

    public void denyAll(String reason) {
        pending.keySet().forEach(callId -> deny(callId, reason));
    }

    private boolean complete(String callId, Approval approval) {
        CompletableFuture<Approval> future = pending.remove(callId);
        return future != null && future.complete(approval);
    }
}
