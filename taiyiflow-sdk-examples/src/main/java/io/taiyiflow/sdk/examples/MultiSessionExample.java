package io.taiyiflow.sdk.examples;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.hooks.StreamHook;

import java.util.concurrent.CountDownLatch;

/**
 * Example: Multi-session parallel execution.
 * Demonstrates running multiple concurrent chat sessions.
 */
public class MultiSessionExample {

    public static void main(String[] args) throws Exception {
        String baseUrl = System.getenv("TAIYIFLOW_BASE_URL");
        String apiKey = System.getenv("TAIYIFLOW_API_KEY");
        String flowId = System.getenv("TAIYIFLOW_FLOW_ID");

        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .flowId(flowId)
                .maxSessions(10)
                .build();

        String[] questions = {
                "什么是人工智能？",
                "解释一下量子计算的基本原理。",
                "Java 虚拟线程有什么优势？"
        };

        CountDownLatch latch = new CountDownLatch(questions.length);

        for (int i = 0; i < questions.length; i++) {
            final int idx = i;
            AgentSession session = agent.newSession("session-" + i);
            session.onStream(new StreamHook() {
                @Override
                public void onChunk(String sessionId, String chunk) {
                    System.out.print("[Session-" + idx + "] " + chunk);
                }

                @Override
                public void onClose(String sessionId, String reason) {
                    System.out.println("\n[Session-" + idx + "] Closed: " + reason);
                    latch.countDown();
                }

                @Override
                public void onError(String sessionId, Throwable error) {
                    System.err.println("[Session-" + idx + "] Error: " + error.getMessage());
                    latch.countDown();
                }
            });

            session.chat(questions[i]);
            System.out.println("Started session-" + idx + ": " + questions[i]);
        }

        latch.await();
        agent.shutdown();
        System.out.println("All sessions completed.");
    }
}
