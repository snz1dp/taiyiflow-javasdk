package io.taiyiflow.sdk.plugins;

import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.plugins.context.SessionContextPlugin;
import io.taiyiflow.sdk.plugins.files.FilePlugin;
import io.taiyiflow.sdk.plugins.interaction.AskUserPlugin;
import io.taiyiflow.sdk.plugins.interaction.UserInteractionHandler;
import io.taiyiflow.sdk.plugins.media.MediaUnderstandingPlugin;
import io.taiyiflow.sdk.plugins.schedule.ScheduleTools;
import io.taiyiflow.sdk.plugins.search.SearchPlugin;
import io.taiyiflow.sdk.plugins.shell.ShellPlugin;
import io.taiyiflow.sdk.plugins.skill.SkillTools;
import io.taiyiflow.sdk.plugins.todo.TodoPlugin;
import io.taiyiflow.sdk.transport.rest.RestClient;

import java.nio.file.Path;
import java.util.List;

/**
 * Convenience registrar for all built-in plugins.
 */
public final class BuiltinPlugins {

    private BuiltinPlugins() {
    }

    /**
     * Register all built-in plugins with default sandbox settings.
     */
    public static void registerAll(PluginManager pm, Path sandboxRoot, List<String> allowedShellCommands) {
        registerAll(pm, sandboxRoot, allowedShellCommands,
                io.taiyiflow.sdk.plugins.shell.ShellPlugin.defaultTimeoutSeconds());
    }

    /** Register all built-in plugins with an explicit shell command timeout (seconds). */
    public static void registerAll(PluginManager pm, Path sandboxRoot, List<String> allowedShellCommands,
                                   long shellTimeoutSeconds) {
        io.taiyiflow.sdk.plugins.workspace.WorkspaceAudit audit =
                new io.taiyiflow.sdk.plugins.workspace.WorkspaceAudit(sandboxRoot);
        ShellPlugin.register(pm, sandboxRoot, allowedShellCommands, shellTimeoutSeconds, audit);
        FilePlugin.register(pm, sandboxRoot, audit);
        SearchPlugin.register(pm, sandboxRoot);
    }

    /**
     * Register only read-only plugins (File read, Search).
     */
    public static void registerReadOnly(PluginManager pm, Path sandboxRoot) {
        SearchPlugin.register(pm, sandboxRoot);
        FilePlugin.registerReadOnly(pm, sandboxRoot);
    }

    public static MediaUnderstandingPlugin registerMedia(PluginManager pm, RestClient restClient) {
        return MediaUnderstandingPlugin.register(pm, restClient);
    }

    public static void registerSchedules(PluginManager pm, RestClient restClient) {
        ScheduleTools.register(pm, restClient);
    }

    public static void registerSkills(PluginManager pm, io.taiyiflow.sdk.core.skill.SkillSupplier supplier,
                                      io.taiyiflow.sdk.plugins.skill.SkillManager manager,
                                      List<String> allowedRequestModes) {
        SkillTools.register(pm, supplier, manager, allowedRequestModes);
    }

    public static void registerSessionContext(PluginManager pm, SessionContextPlugin.MetadataProvider metadata) {
        SessionContextPlugin.register(pm, metadata);
    }

    public static TodoPlugin registerTodo(PluginManager pm) {
        return TodoPlugin.register(pm);
    }

    public static void registerAskUser(PluginManager pm, UserInteractionHandler handler) {
        AskUserPlugin.register(pm, handler);
    }
}
