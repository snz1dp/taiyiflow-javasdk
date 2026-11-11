package io.taiyiflow.sdk.examples;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.hooks.StreamHook;

public class RetryRepeatExample {

    public static void main(String[] args) throws Exception {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl(System.getenv("TAIYIFLOW_BASE_URL"))
                .apiKey(System.getenv("TAIYIFLOW_API_KEY"))
                .flowId(System.getenv("TAIYIFLOW_FLOW_ID"))
                .build();
        try {
            AgentSession session = agent.newSession();
            session.onStream(new StreamHook() {
                @Override
                public void onChunk(String sessionId, String chunk) {
                    System.out.print(chunk);
                }
            });

            session.chat("请总结 Java 虚拟线程的三个使用注意事项。");
            session.await();

            System.out.println("\n--- Retry ---");
            session.retry();
            session.await();

            System.out.println("\n--- Repeat original input ---");
            session.repeat();
            session.await();
        } finally {
            agent.shutdown();
        }
    }
}
