package io.taiyiflow.sdk.worker;

import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.transport.envelope.ToolCall;

import java.util.concurrent.CompletableFuture;

final class WorkerApprovalHook implements PermissionHook {
    private final PermissionPolicy policy;
    private final ApprovalChannel channel;
    private final PermissionHook approvalHandler;
    private final ApprovalAudit approvalAudit;

    WorkerApprovalHook(PermissionPolicy policy, ApprovalChannel channel,
                       PermissionHook approvalHandler, ApprovalAudit approvalAudit) {
        this.policy = policy;
        this.channel = channel;
        this.approvalHandler = approvalHandler;
        this.approvalAudit = approvalAudit;
    }

    @Override
    public CompletableFuture<Decision> onPermission(String sessionId, ToolCall toolCall) {
        Decision policyDecision = policy.onPermission(sessionId, toolCall).join();
        if (policyDecision != Decision.ASK) {
            return CompletableFuture.completedFuture(policyDecision);
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
        final CompletableFuture<Decision> decisionFuture;
        try {
            decisionFuture = approvalHandler.onPermission(sessionId, toolCall);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
        if (channel == ApprovalChannel.PLATFORM) {
            return decisionFuture.thenApply(WorkerApprovalHook::requireFinalDecision);
        }
        return decisionFuture.thenCompose(decision -> {
            Decision finalDecision = requireFinalDecision(decision);
            return approvalAudit.record(sessionId, toolCall, finalDecision)
                    .thenApply(ignored -> finalDecision);
        });
    }

    private static Decision requireFinalDecision(Decision decision) {
        if (decision == null || decision == Decision.ASK) {
            throw new IllegalStateException("Approval handler must return ALLOW or DENY");
        }
        return decision;
    }
}
