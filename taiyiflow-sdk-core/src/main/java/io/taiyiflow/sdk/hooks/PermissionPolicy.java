package io.taiyiflow.sdk.hooks;

import io.taiyiflow.sdk.transport.envelope.ToolCall;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public enum PermissionPolicy implements PermissionHook {
    AUTO,
    CONFIRM,
    READONLY;

    private static final Set<String> MUTATING_TOOLS = Set.of(
            "shell", "bash", "execute_command", "shell_exec", "task_stop", "task_delete",
            "task_cleanup", "todo_write", "file_edit", "document_unstructured", "write_file");

    @Override
    public CompletableFuture<Decision> onPermission(String sessionId, ToolCall toolCall) {
        if (!isMutating(toolCall.getName()) || this == AUTO) {
            return CompletableFuture.completedFuture(Decision.ALLOW);
        }
        Decision decision = this == CONFIRM ? Decision.ASK : Decision.DENY;
        return CompletableFuture.completedFuture(decision);
    }

    private static boolean isMutating(String toolName) {
        if (toolName != null && toolName.startsWith("taiyiflow-in-chrome__")) {
            String suffix = toolName.substring("taiyiflow-in-chrome__".length());
            return !Set.of("tabs_context", "read_text", "read_console").contains(suffix);
        }
        if (toolName != null && toolName.startsWith("taiyiflow-computer__")) {
            String suffix = toolName.substring("taiyiflow-computer__".length());
            return !Set.of("displays_context", "windows_context", "screenshot").contains(suffix);
        }
        return MUTATING_TOOLS.contains(toolName);
    }
}
