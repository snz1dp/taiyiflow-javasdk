package io.taiyiflow.sdk.plugins;

import io.taiyiflow.sdk.core.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinPluginsTest {

    @TempDir
    Path tempDir;

    @Test
    void registerAllAddsAllPlugins() {
        PluginManager pm = new PluginManager();
        BuiltinPlugins.registerAll(pm, tempDir, List.of("echo", "ls"));

        assertTrue(pm.hasHandler("shell"));
        assertTrue(pm.hasHandler("bash"));
        assertTrue(pm.hasHandler("execute_command"));
        assertTrue(pm.hasHandler("read_file"));
        assertTrue(pm.hasHandler("write_file"));
        assertTrue(pm.hasHandler("list_files"));
        assertTrue(pm.hasHandler("search"));
        assertTrue(pm.hasHandler("grep"));
    }

    @Test
    void registerReadOnlyExcludesWriteAndShell() {
        PluginManager pm = new PluginManager();
        BuiltinPlugins.registerReadOnly(pm, tempDir);

        assertFalse(pm.hasHandler("shell"));
        assertFalse(pm.hasHandler("write_file"));
        assertTrue(pm.hasHandler("read_file"));
        assertTrue(pm.hasHandler("list_files"));
        assertTrue(pm.hasHandler("search"));
        assertTrue(pm.hasHandler("grep"));
    }
}
