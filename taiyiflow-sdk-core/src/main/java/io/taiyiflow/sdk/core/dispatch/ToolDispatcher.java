package io.taiyiflow.sdk.core.dispatch;

import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
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
    private final ToolRuntime toolRuntime;

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
        this(pluginManager, toolHooks, permissionHooks, permissionTimeout, approvalBroker, null);
    }

    public ToolDispatcher(PluginManager pluginManager,
                          List<ToolExecutionHook> toolHooks,
                          List<PermissionHook> permissionHooks,
                          ToolRuntime toolRuntime) {
        this(pluginManager, toolHooks, permissionHooks, Duration.ofSeconds(300),
                new ApprovalBroker(), toolRuntime);
    }

    public ToolDispatcher(PluginManager pluginManager,
                          List<ToolExecutionHook> toolHooks,
                          List<PermissionHook> permissionHooks,
                          Duration permissionTimeout,
                          ToolRuntime toolRuntime) {
        this(pluginManager, toolHooks, permissionHooks, permissionTimeout,
                new ApprovalBroker(), toolRuntime);
    }

    public ToolDispatcher(PluginManager pluginManager,
                          List<ToolExecutionHook> toolHooks,
                          List<PermissionHook> permissionHooks,
                          Duration permissionTimeout,
                          ApprovalBroker approvalBroker,
                          ToolRuntime toolRuntime) {
        this.pluginManager = pluginManager;
        this.toolHooks = toolHooks;
        this.permissionHooks = permissionHooks;
        this.permissionTimeout = permissionTimeout;
        this.approvalBroker = approvalBroker;
        this.toolRuntime = toolRuntime;
    }

    /**
     * Dispatch a tool call through the Hook chain using the legacy standalone context.
     *
     * @param sessionId the session identifier
     * @param toolCall the tool call to execute
     * @return the tool result
     */
    public ToolResult dispatch(String sessionId, ToolCall toolCall) {
        return dispatch(ToolExecutionContext.standalone(sessionId), toolCall);
    }

    /**
     * Dispatch with an explicit immutable execution context. Permission checks and before hooks
     * complete before the shared runtime is asked for an execution permit.
     */
    public ToolResult dispatch(ToolExecutionContext context, ToolCall toolCall) {
        String sessionId = context.getSessionId();
        log.debug("Dispatching tool call: {} in session {}", toolCall.getName(), sessionId);
        if (context.getAllowedTools() != null && !context.getAllowedTools().contains(toolCall.getName())) {
            return ToolResult.error(toolCall.getId(), "Tool is not present in the subtask capability snapshot");
        }
        if (("ask".equals(context.getRequestMode()) || "plan".equals(context.getRequestMode()))
                && PermissionPolicy.isMutating(toolCall.getName())) {
            return ToolResult.error(toolCall.getId(), "Mutating tools are disabled in " + context.getRequestMode()
                    + " mode");
        }

        // 1. Permission check, including ASK approval wait: no execution permit is held here.
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

        // 2. Before hooks (can modify the tool call): also outside the scheduler permit.
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

        // 3. Only takeover/plugin execution occupies a shared runtime permit.
        ToolCall executionCall = currentCall;
        ExecutionOutcome outcome;
        try {
            if (toolRuntime == null) {
                outcome = execute(context, sessionId, executionCall, toolCall.getId());
            } else {
                outcome = toolRuntime.execute(context,
                        () -> execute(context, sessionId, executionCall, toolCall.getId()));
            }
        } catch (ToolExecutionException e) {
            log.warn("Tool execution was not completed for session {}: {}", sessionId, e.getMessage());
            return ToolResult.error(toolCall.getId(), e.getMessage());
        }

        ToolResult result = outcome.result;
        if (!outcome.runAfterHooks) {
            return result;
        }

        // 4. After hooks run after permit release and can observe/modify the result.
        for (ToolExecutionHook hook : toolHooks) {
            try {
                ToolResult modified = hook.after(sessionId, executionCall, result);
                if (modified != null) {
                    result = modified;
                }
            } catch (Exception e) {
                log.error("ToolExecutionHook.after failed", e);
            }
        }

        return result;
    }

    private ExecutionOutcome execute(ToolExecutionContext context, String sessionId,
                                     ToolCall currentCall, String originalCallId) {
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
                return new ExecutionOutcome(
                        ToolResult.error(originalCallId, "Takeover hook failed: " + e.getMessage()),
                        false);
            }
        }

        if (result == null) {
            result = pluginManager.execute(context, currentCall);
        }
        return new ExecutionOutcome(result, true);
    }

    private static final class ExecutionOutcome {
        private final ToolResult result;
        private final boolean runAfterHooks;

        private ExecutionOutcome(ToolResult result, boolean runAfterHooks) {
            this.result = result;
            this.runAfterHooks = runAfterHooks;
        }
    }

    private PermissionHook.Decision await(CompletableFuture<PermissionHook.Decision> future)
            throws InterruptedException, java.util.concurrent.ExecutionException, TimeoutException {
        if (permissionTimeout.isZero()) {
            return future.get();
        }
        return future.get(permissionTimeout.toNanos(), TimeUnit.NANOSECONDS);
    }
}
