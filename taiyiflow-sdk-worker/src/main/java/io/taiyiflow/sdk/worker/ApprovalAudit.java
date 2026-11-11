package io.taiyiflow.sdk.worker;

import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.transport.envelope.ToolCall;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface ApprovalAudit {
    CompletableFuture<Void> record(String sessionId, ToolCall toolCall, PermissionHook.Decision decision);
}
