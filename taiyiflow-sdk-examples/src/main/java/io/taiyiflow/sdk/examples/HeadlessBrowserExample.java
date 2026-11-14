package io.taiyiflow.sdk.examples;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.browser.BrowserSettings;
import io.taiyiflow.sdk.browser.BrowserToolProvider;

import java.time.Duration;
import java.util.Set;

public final class HeadlessBrowserExample {
    public static void main(String[] args) throws Exception {
        TaiyiAgent agent = TaiyiAgent.builder().baseUrl(FlowMetadataExample.env("TAIYIFLOW_BASE_URL"))
                .apiKey(FlowMetadataExample.env("TAIYIFLOW_API_KEY"))
                .flowId(FlowMetadataExample.env("TAIYIFLOW_FLOW_ID")).build();
        try {
            BrowserToolProvider.register(agent.getPluginManager(), agent.getRestClient(), BrowserSettings.builder()
                    .mode(BrowserSettings.Mode.HEADLESS)
                    .allowedHosts(Set.of(FlowMetadataExample.env("TAIYIFLOW_BROWSER_HOST")))
                    .build());
            AgentSession session = agent.newSession();
            session.onStream(new io.taiyiflow.sdk.hooks.StreamHook() {
                @Override public void onChunk(String sessionId, String chunk) { System.out.print(chunk); }
            });
            session.chat("新建页面，读取标题和正文摘要，不要提交表单。目标：https://"
                    + FlowMetadataExample.env("TAIYIFLOW_BROWSER_HOST"));
            if (!session.await(Duration.ofMinutes(2))) throw new IllegalStateException("Browser example timed out");
        } finally {
            agent.shutdown();
        }
    }
}
