package io.taiyiflow.sdk.worker;

import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.transport.envelope.ToolCall;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class WorkerApprovalHook implements PermissionHook {
    // The policy gate is automated and must not block a tool dispatch forever if a custom policy
    // returns a future that never completes; human approval waits happen on approvalHandler below.
    private static final long POLICY_EVALUATE_TIMEOUT_SECONDS = 30;

    private final PermissionHook policy;
    private final ApprovalChannel channel;
    private final PermissionHook approvalHandler;
    private final ApprovalAudit approvalAudit;

    WorkerApprovalHook(PermissionHook policy, ApprovalChannel channel,
                       PermissionHook approvalHandler, ApprovalAudit approvalAudit) {
        this.policy = policy;
        this.channel = channel;
        this.approvalHandler = approvalHandler;
        this.approvalAudit = approvalAudit;
    }

    @Override
    public CompletableFuture<Decision> onPermission(String sessionId, ToolCall toolCall) {
        return evaluate(sessionId, toolCall).thenApply(PermissionResult::decision);
    }

    @Override
    public CompletableFuture<PermissionResult> evaluate(String sessionId, ToolCall toolCall) {
        final PermissionResult policyResult;
        try {
            policyResult = policy.evaluate(sessionId, toolCall)
                    .get(POLICY_EVALUATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            return CompletableFuture.failedFuture(cause != null ? cause : exception);
        } catch (TimeoutException exception) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Permission policy evaluation timed out after " + POLICY_EVALUATE_TIMEOUT_SECONDS + "s",
                    exception));
        }
        if (policyResult.decision() != Decision.ASK) {
            return CompletableFuture.completedFuture(policyResult);
        }
        if (approvalHandler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    channel == ApprovalChannel.PLATFORM
                            ? "Platform approval is unavailable; configure an approvalHandler backed by the platform"
                            : "Local approval callback is not configured"));
        }
        if (channel == ApprovalChannel.LOCAL_CALLBACK && approvalAudit == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Local approval decisions require a platform approvalAudit"));
        }
        final CompletableFuture<PermissionResult> decisionFuture;
        try {
            decisionFuture = approvalHandler.evaluate(sessionId, toolCall);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
        if (channel == ApprovalChannel.PLATFORM) {
            return decisionFuture.thenApply(WorkerApprovalHook::requireFinalDecision);
        }
        return decisionFuture.thenCompose(result -> {
            PermissionResult finalResult = requireFinalDecision(result);
            return approvalAudit.record(sessionId, toolCall, finalResult.decision())
                    .thenApply(ignored -> finalResult);
        });
    }

    private static PermissionResult requireFinalDecision(PermissionResult result) {
        if (result == null || result.decision() == null || result.decision() == Decision.ASK) {
            throw new IllegalStateException("Approval handler must return ALLOW or DENY");
        }
        return result;
    }
}