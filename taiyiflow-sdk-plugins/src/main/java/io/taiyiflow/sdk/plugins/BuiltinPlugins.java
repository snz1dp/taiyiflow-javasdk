package io.taiyiflow.sdk.plugins;

import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.plugins.files.FilePlugin;
import io.taiyiflow.sdk.plugins.search.SearchPlugin;
import io.taiyiflow.sdk.plugins.shell.ShellPlugin;

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
        ShellPlugin.register(pm, sandboxRoot, allowedShellCommands);
        FilePlugin.register(pm, sandboxRoot);
        SearchPlugin.register(pm, sandboxRoot);
    }

    /**
     * Register only read-only plugins (File read, Search).
     */
    public static void registerReadOnly(PluginManager pm, Path sandboxRoot) {
        SearchPlugin.register(pm, sandboxRoot);
        // FilePlugin read only
        FilePlugin fp = new FilePlugin(sandboxRoot);
        pm.register("read_file", fp::readFile);
        pm.register("list_files", fp::listFiles);
    }
}
