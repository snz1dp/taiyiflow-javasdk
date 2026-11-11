package io.taiyiflow.sdk.examples;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.transport.envelope.StreamEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LocalApprovalExample {

    public static void main(String[] args) throws Exception {
        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl(System.getenv("TAIYIFLOW_BASE_URL"))
                .apiKey(System.getenv("TAIYIFLOW_API_KEY"))
                .flowId(System.getenv("TAIYIFLOW_FLOW_ID"))
                .permissionPolicy(PermissionPolicy.CONFIRM)
                .permissionTimeout(Duration.ofMinutes(5))
                .build();
        try {
            CountDownLatch approvalRequested = new CountDownLatch(1);
            AtomicReference<String> toolCallId = new AtomicReference<>();
            AgentSession session = agent.newSession();
            session.onStream(new StreamHook() {
                @Override
                public void onChunk(String sessionId, String chunk) {
                    System.out.print(chunk);
                }

                @Override
                public void onEvent(String sessionId, StreamEvent event) {
                    if (event.isClientTool()) {
                        toolCallId.set(event.getData().required("function").required("id").asText());
                        System.out.println("\nApproval requested: " + event.getData());
                        approvalRequested.countDown();
                    }
                }
            });

            session.chat("请使用客户端文件工具在工作目录写入 approval-demo.txt，内容为 approved。");
            if (!approvalRequested.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("No local approval request arrived");
            }
            System.out.print("Type approve or deny: ");
            String decision = new BufferedReader(new InputStreamReader(System.in)).readLine();
            submitDecision(session, toolCallId.get(), decision);
            session.await();
        } finally {
            agent.shutdown();
        }
    }

    private static void submitDecision(AgentSession session, String toolCallId, String decision)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean completed = "deny".equalsIgnoreCase(decision)
                    ? session.deny(toolCallId, "Denied by local operator")
                    : session.approve(toolCallId);
            if (completed) {
                return;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("Approval request is no longer pending: " + toolCallId);
    }
}
