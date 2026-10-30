package io.taiyiflow.sdk.core.dispatch;

import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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

    public ToolDispatcher(PluginManager pluginManager,
                          List<ToolExecutionHook> toolHooks,
                          List<PermissionHook> permissionHooks) {
        this.pluginManager = pluginManager;
        this.toolHooks = toolHooks;
        this.permissionHooks = permissionHooks;
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
                PermissionHook.Decision decision = hook.onPermission(sessionId, toolCall);
                if (decision == PermissionHook.Decision.DENY) {
                    log.info("Tool call denied by permission hook: {}", toolCall.getName());
                    return ToolResult.error(toolCall.getId(), "Permission denied by hook");
                }
                // ASK is treated as DENY in automated context
                if (decision == PermissionHook.Decision.ASK) {
                    log.info("Tool call requires approval (treated as deny): {}", toolCall.getName());
                    return ToolResult.error(toolCall.getId(), "Permission requires manual approval");
                }
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
}
