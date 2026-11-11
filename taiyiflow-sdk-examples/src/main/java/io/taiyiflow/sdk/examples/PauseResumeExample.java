package io.taiyiflow.sdk.examples;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.hooks.StreamHook;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PauseResumeExample {

    public static void main(String[] args) throws Exception {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl(System.getenv("TAIYIFLOW_BASE_URL"))
                .apiKey(System.getenv("TAIYIFLOW_API_KEY"))
                .flowId(System.getenv("TAIYIFLOW_FLOW_ID"))
                .build();
        try {
            CountDownLatch firstChunk = new CountDownLatch(1);
            AgentSession session = agent.newSession();
            session.onStream(new StreamHook() {
                @Override
                public void onChunk(String sessionId, String chunk) {
                    System.out.print(chunk);
                    firstChunk.countDown();
                }
            });

            session.chat("请分步骤详细说明如何设计一个可靠的分布式任务调度器。");
            if (!firstChunk.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("No response arrived before pause");
            }
            session.pause();
            System.out.println("\n--- Paused, discovering a resumable checkpoint ---");

            session.resume();
            if (!session.await(Duration.ofSeconds(120))) {
                throw new IllegalStateException("Resume did not finish in time");
            }
            System.out.println("\n--- Resumed and completed ---");
        } finally {
            agent.shutdown();
        }
    }
}
