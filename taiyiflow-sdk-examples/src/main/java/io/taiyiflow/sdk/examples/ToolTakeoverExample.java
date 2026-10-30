package io.taiyiflow.sdk.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;

/**
 * Example: Tool takeover - intercept tool calls and provide custom implementations.
 */
public class ToolTakeoverExample {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String baseUrl = System.getenv("TAIYIFLOW_BASE_URL");
        String apiKey = System.getenv("TAIYIFLOW_API_KEY");
        String flowId = System.getenv("TAIYIFLOW_FLOW_ID");

        TaiyiAgent agent = TaiyiAgent.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .flowId(flowId)
                // Global tool hook: takeover "get_weather" tool
                .onToolExecution(new ToolExecutionHook() {
                    @Override
                    public ToolResult takeover(String sessionId, ToolCall toolCall) {
                        if ("get_weather".equals(toolCall.getName())) {
                            System.out.println("[Takeover] get_weather called with: " + toolCall.getArguments());
                            ObjectNode weather = mapper.createObjectNode();
                            weather.put("city", "Beijing");
                            weather.put("temperature", 22);
                            weather.put("condition", "Sunny");
                            weather.put("humidity", "45%");
                            return ToolResult.success(toolCall.getId(), weather);
                        }
                        return null; // Let built-in handle other tools
                    }
                })
                // Permission hook: deny shell commands
                .onPermission(new PermissionHook() {
                    @Override
                    public Decision onPermission(String sessionId, ToolCall toolCall) {
                        if ("shell".equals(toolCall.getName()) || "bash".equals(toolCall.getName())) {
                            System.out.println("[Permission] Denied shell command");
                            return Decision.DENY;
                        }
                        return Decision.ALLOW;
                    }
                })
                .build();

        AgentSession session = agent.newSession();
        session.onStream(new StreamHook() {
            @Override
            public void onChunk(String sessionId, String chunk) {
                System.out.print(chunk);
            }

            @Override
            public void onClose(String sessionId, String reason) {
                System.out.println("\n--- Done ---");
            }
        });

        session.chat("北京今天天气怎么样？");
        session.await();
        agent.shutdown();
    }
}
