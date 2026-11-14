package io.taiyiflow.sdk.starter;

import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.browser.BrowserRuntime;
import io.taiyiflow.sdk.browser.BrowserSettings;
import io.taiyiflow.sdk.browser.BrowserToolProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

@AutoConfiguration(after = TaiyiFlowAutoConfiguration.class)
@ConditionalOnClass(BrowserSettings.class)
@ConditionalOnBean(TaiyiAgent.class)
public class TaiyiFlowBrowserAutoConfiguration {
    @Bean(destroyMethod = "close")
    public BrowserRuntime taiyiFlowBrowserRuntime(TaiyiAgent agent, TaiyiFlowProperties properties) {
        TaiyiFlowProperties.Browser browser = properties.getBrowser();
        if ("DISABLED".equalsIgnoreCase(browser.getMode())) return null;
        BrowserSettings.Builder settings = BrowserSettings.builder()
                .mode(BrowserSettings.Mode.valueOf(browser.getMode()))
                .launchArguments(browser.getLaunchArguments())
                .actionTimeout(browser.getActionTimeout())
                .maxPagesPerSession(browser.getMaxPagesPerSession())
                .maxReadTextCharacters(browser.getMaxReadTextCharacters())
                .maxConsoleMessages(browser.getMaxConsoleMessages())
                .maxScreenshotBytes(browser.getMaxScreenshotBytes())
                .executeJsEnabled(browser.isExecuteJsEnabled())
                .allowedHosts(browser.getAllowedHosts())
                .allowedCidrs(browser.getAllowedCidrs())
                .remoteEndpoint(browser.getRemoteEndpoint());
        if (browser.getExecutablePath() != null) settings.executablePath(Path.of(browser.getExecutablePath()));
        browser.getRemoteHeaders().forEach(settings::remoteHeader);
        return BrowserToolProvider.register(agent.getPluginManager(), agent.getRestClient(), settings.build());
    }
}
