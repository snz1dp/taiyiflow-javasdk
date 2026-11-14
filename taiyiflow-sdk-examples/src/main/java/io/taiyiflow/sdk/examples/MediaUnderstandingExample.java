package io.taiyiflow.sdk.examples;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.plugins.BuiltinPlugins;

import java.time.Duration;

public final class MediaUnderstandingExample {
    public static void main(String[] args) throws Exception {
        TaiyiAgent agent = TaiyiAgent.builder().baseUrl(FlowMetadataExample.env("TAIYIFLOW_BASE_URL"))
                .apiKey(FlowMetadataExample.env("TAIYIFLOW_API_KEY"))
                .flowId(System.getenv("TAIYIFLOW_FLOW_ID")).build();
        try {
            BuiltinPlugins.registerMedia(agent.getPluginManager(), agent.getRestClient());
            AgentSession session = agent.newSession();
            session.onStream(new io.taiyiflow.sdk.hooks.StreamHook() {
                @Override public void onChunk(String sessionId, String chunk) { System.out.print(chunk); }
            });
            session.chat("请使用媒体理解工具分析这个文件：" + FlowMetadataExample.env("TAIYIFLOW_MEDIA_PATH"));
            if (!session.await(Duration.ofMinutes(3))) throw new IllegalStateException("Media understanding timed out");
        } finally {
            agent.shutdown();
        }
    }
}
