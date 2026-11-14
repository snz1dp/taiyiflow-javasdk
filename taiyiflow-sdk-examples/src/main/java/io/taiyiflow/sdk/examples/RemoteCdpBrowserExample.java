package io.taiyiflow.sdk.examples;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.browser.BrowserSettings;
import io.taiyiflow.sdk.browser.BrowserToolProvider;

import java.time.Duration;
import java.util.Set;

public final class RemoteCdpBrowserExample {
    public static void main(String[] args) throws Exception {
        TaiyiAgent agent = TaiyiAgent.builder().baseUrl(FlowMetadataExample.env("TAIYIFLOW_BASE_URL"))
                .apiKey(FlowMetadataExample.env("TAIYIFLOW_API_KEY"))
                .flowId(System.getenv("TAIYIFLOW_FLOW_ID")).build();
        try {
            BrowserSettings.Builder settings = BrowserSettings.builder().mode(BrowserSettings.Mode.REMOTE_CDP)
                    .remoteEndpoint(FlowMetadataExample.env("TAIYIFLOW_CDP_ENDPOINT"))
                    .allowedHosts(Set.of(FlowMetadataExample.env("TAIYIFLOW_BROWSER_HOST")));
            String authorization = System.getenv("TAIYIFLOW_CDP_AUTHORIZATION");
            if (authorization != null && !authorization.isBlank()) settings.remoteHeader("Authorization", authorization);
            BrowserToolProvider.register(agent.getPluginManager(), agent.getRestClient(), settings.build());
            AgentSession session = agent.newSession();
            session.onStream(new io.taiyiflow.sdk.hooks.StreamHook() {
                @Override public void onChunk(String sessionId, String chunk) { System.out.print(chunk); }
            });
            session.chat("只操作本会话新建的页面并读取内容，不操作已有页面。目标：https://"
                    + FlowMetadataExample.env("TAIYIFLOW_BROWSER_HOST"));
            if (!session.await(Duration.ofMinutes(2))) throw new IllegalStateException("Remote browser example timed out");
        } finally {
            agent.shutdown();
        }
    }
}
