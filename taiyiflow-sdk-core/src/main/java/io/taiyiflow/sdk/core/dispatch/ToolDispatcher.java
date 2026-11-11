package io.taiyiflow.sdk.core.dispatch;

import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Dispatches tool calls through the Hook chain:
 * 1. PermissionHook (approve/deny)
 * 2. ToolExecutionHook.before (modify)
 * 3. ToolExecutionHook.takeover (caller takes over) OR PluginManager.execute (built-in)
 * 4. ToolExecutionHook.after (observe/modify result)
 */
public class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);

    private final PluginManager pluginManager;
    private final List<ToolExecutionHook> toolHooks;
    private final List<PermissionHook> permissionHooks;
    private final Duration permissionTimeout;
    private final ApprovalBroker approvalBroker;

    public ToolDispatcher(PluginManager pluginManager,
                          List<ToolExecutionHook> toolHooks,
                          List<PermissionHook> permissionHooks) {
        this(pluginManager, toolHooks, permissionHooks, Duration.ofSeconds(300));
    }

    public ToolDispatcher(PluginManager pluginManager,
                          List<ToolExecutionHook> toolHooks,
                          List<PermissionHook> permissionHooks,
                          Duration permissionTimeout) {
        this(pluginManager, toolHooks, permissionHooks, permissionTimeout, new ApprovalBroker());
    }

    public ToolDispatcher(PluginManager pluginManager,
                          List<ToolExecutionHook> toolHooks,
                          List<PermissionHook> permissionHooks,
                          Duration permissionTimeout,
                          ApprovalBroker approvalBroker) {
        this.pluginManager = pluginManager;
        this.toolHooks = toolHooks;
        this.permissionHooks = permissionHooks;
        this.permissionTimeout = permissionTimeout;
        this.approvalBroker = approvalBroker;
    }

    /**
     * Dispatch a tool call through the Hook chain.
     *
     * @param sessionId the session identifier
     * @param toolCall the tool call to execute
     * @return the tool result
     */
    public ToolResult dispatch(String sessionId, ToolCall toolCall) {
        log.debug("Dispatching tool call: {} in session {}", toolCall.getName(), sessionId);

        // 1. Permission check
        for (PermissionHook hook : permissionHooks) {
            try {
                PermissionHook.Decision decision = await(hook.onPermission(sessionId, toolCall));
                if (decision == PermissionHook.Decision.ASK) {
                    ApprovalBroker.Approval approval = approvalBroker.await(toolCall, permissionTimeout);
                    decision = approval.decision();
                    if (decision == PermissionHook.Decision.DENY) {
                        log.info("Tool call denied by user: {}", toolCall.getName());
                        return ToolResult.error(toolCall.getId(), approval.reason());
                    }
                }
                if (decision == PermissionHook.Decision.DENY) {
                    log.info("Tool call denied by permission hook: {}", toolCall.getName());
                    return ToolResult.error(toolCall.getId(), "Permission denied by hook");
                }
            } catch (TimeoutException e) {
                return ToolResult.error(toolCall.getId(), "Permission check timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error(toolCall.getId(), "Permission check interrupted");
            } catch (Exception e) {
                log.error("PermissionHook failed", e);
                return ToolResult.error(toolCall.getId(), "Permission check failed: " + e.getMessage());
            }
        }

        // 2. Before hooks (can modify the tool call)
        ToolCall currentCall = toolCall;
        for (ToolExecutionHook hook : toolHooks) {
            try {
                ToolCall modified = hook.before(sessionId, currentCall);
                if (modified == null) {
                    return ToolResult.error(toolCall.getId(), "Tool call cancelled by before hook");
                }
                currentCall = modified;
            } catch (Exception e) {
                log.error("ToolExecutionHook.before failed", e);
                return ToolResult.error(toolCall.getId(), "Before hook failed: " + e.getMessage());
            }
        }

        // 3. Execute: try takeover first, then built-in plugin
        ToolResult result = null;
        for (ToolExecutionHook hook : toolHooks) {
            try {
                ToolResult takeoverResult = hook.takeover(sessionId, currentCall);
                if (takeoverResult != null) {
                    log.debug("Tool call taken over by hook: {}", currentCall.getName());
                    result = takeoverResult;
                    break;
                }
            } catch (Exception e) {
                log.error("ToolExecutionHook.takeover failed", e);
                return ToolResult.error(toolCall.getId(), "Takeover hook failed: " + e.getMessage());
            }
        }

        if (result == null) {
            result = pluginManager.execute(currentCall);
        }

        // 4. After hooks (can observe/modify result)
        for (ToolExecutionHook hook : toolHooks) {
            try {
                ToolResult modified = hook.after(sessionId, currentCall, result);
                if (modified != null) {
                    result = modified;
                }
            } catch (Exception e) {
                log.error("ToolExecutionHook.after failed", e);
            }
        }

        return result;
    }

    private PermissionHook.Decision await(CompletableFuture<PermissionHook.Decision> future)
            throws InterruptedException, java.util.concurrent.ExecutionException, TimeoutException {
        if (permissionTimeout.isZero()) {
            return future.get();
        }
        return future.get(permissionTimeout.toNanos(), TimeUnit.NANOSECONDS);
    }
}
