package io.taiyiflow.sdk.browser;

import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.rest.RestClient;

public final class BrowserToolProvider {
    private BrowserToolProvider() {
    }

    public static BrowserRuntime register(PluginManager pluginManager, RestClient restClient,
                                          BrowserSettings settings) {
        if (settings.getMode() == BrowserSettings.Mode.DISABLED) {
            return null;
        }
        BrowserRuntime runtime = new BrowserRuntime(settings, restClient);
        runtime.register(pluginManager);
        return runtime;
    }
}
