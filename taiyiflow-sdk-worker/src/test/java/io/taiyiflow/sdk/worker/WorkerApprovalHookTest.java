package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerApprovalHookTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingPlatformAdapterFailsOnlyApprovalRequiredCalls() throws Exception {
        WorkerApprovalHook hook = hook(ApprovalChannel.PLATFORM, null, null);

        assertEquals(PermissionHook.Decision.ALLOW,
                hook.onPermission("session-1", call("read_file")).get(1, TimeUnit.SECONDS));
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> hook.onPermission("session-1", call("write_file")).get(1, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("Platform approval is unavailable"));
    }

    @Test
    void platformAdapterDoesNotRequireSeparateAudit() throws Exception {
        WorkerApprovalHook hook = hook(ApprovalChannel.PLATFORM,
                (sessionId, toolCall) -> CompletableFuture.completedFuture(PermissionHook.Decision.ALLOW), null);

        assertEquals(PermissionHook.Decision.ALLOW,
                hook.onPermission("session-1", call("write_file")).get(1, TimeUnit.SECONDS));
    }

    @Test
    void localDecisionIsNotRequestedWhenPlatformAuditIsMissing() {
        AtomicBoolean callbackInvoked = new AtomicBoolean();
        WorkerApprovalHook hook = hook(ApprovalChannel.LOCAL_CALLBACK, (sessionId, toolCall) -> {
            callbackInvoked.set(true);
            return CompletableFuture.completedFuture(PermissionHook.Decision.ALLOW);
        }, null);

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> hook.onPermission("session-1", call("write_file")).get(1, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("approvalAudit"));
        assertFalse(callbackInvoked.get());
    }

    @Test
    void localAllowWaitsForPlatformAudit() throws Exception {
        CompletableFuture<Void> auditCompletion = new CompletableFuture<>();
        AtomicReference<PermissionHook.Decision> audited = new AtomicReference<>();
        WorkerApprovalHook hook = hook(ApprovalChannel.LOCAL_CALLBACK,
                (sessionId, toolCall) -> CompletableFuture.completedFuture(PermissionHook.Decision.ALLOW),
                (sessionId, toolCall, decision) -> {
                    audited.set(decision);
                    return auditCompletion;
                });

        CompletableFuture<PermissionHook.Decision> decision = hook.onPermission("session-1", call("write_file"));
        assertFalse(decision.isDone());
        assertEquals(PermissionHook.Decision.ALLOW, audited.get());
        auditCompletion.complete(null);
        assertEquals(PermissionHook.Decision.ALLOW, decision.get(1, TimeUnit.SECONDS));
    }

    private WorkerApprovalHook hook(ApprovalChannel channel, PermissionHook handler, ApprovalAudit audit) {
        return new WorkerApprovalHook(PermissionPolicy.CONFIRM, channel, handler, audit);
    }

    private ToolCall call(String name) {
        return new ToolCall("call-1", name, mapper.createObjectNode());
    }
}
