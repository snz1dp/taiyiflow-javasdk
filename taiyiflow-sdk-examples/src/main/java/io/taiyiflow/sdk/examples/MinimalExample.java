package io.taiyiflow.sdk.examples;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.transport.envelope.StreamEvent;

/**
 * Minimal example: 3 lines to stream a chat response.
 *
 * Usage:
 *   export TAIYIFLOW_BASE_URL=https://api.taiyiflow.io
 *   export TAIYIFLOW_API_KEY=your-api-key
 *   export TAIYIFLOW_FLOW_ID=your-flow-id  # optional; defaults to the server default flow
 *   mvn exec:java -pl taiyiflow-sdk-examples
 */
public class MinimalExample {

    public static void main(String[] args) throws Exception {
        String baseUrl = env("TAIYIFLOW_BASE_URL");
        String apiKey = env("TAIYIFLOW_API_KEY");
        String flowId = System.getenv("TAIYIFLOW_FLOW_ID");

        // 1. Build agent
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .flowId(flowId)
                .build();

        // 2. Create session and stream
        AgentSession session = agent.newSession();
        session.onStream(new StreamHook() {
            @Override
            public void onChunk(String sessionId, String chunk) {
                System.out.print(chunk);
            }

            @Override
            public void onClose(String sessionId, String reason) {
                System.out.println("\n--- Stream closed: " + reason + " ---");
            }

            @Override
            public void onError(String sessionId, Throwable error) {
                System.err.println("Error: " + error.getMessage());
            }
        });

        // 3. Chat!
        session.chat("你好，请介绍一下你自己。");
        session.await();
        agent.shutdown();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            System.err.println("Missing environment variable: " + name);
            System.exit(1);
        }
        return value;
    }
}
