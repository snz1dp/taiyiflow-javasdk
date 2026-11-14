package io.taiyiflow.sdk.plugins.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.FlowMetadata;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionContextPluginTest {
    @Test
    void exposesOnlyTrustedIdentityAndReadOnlyCapabilityFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FlowMetadata metadata = new FlowMetadata(mapper.readTree("{\"id\":\"flow-a\",\"owner_appid\":\"owner\","
                + "\"name\":\"Agent\",\"features\":[\"reasoning\"],\"custom_fields\":{\"token\":\"secret\"},"
                + "\"knowledges\":[{\"code\":\"kb\",\"name\":\"Knowledge\",\"type\":\"docs\",\"credential\":\"secret\"}]}"));
        PluginManager plugins = new PluginManager();
        SessionContextPlugin.register(plugins, () -> metadata);
        ToolExecutionContext context = ToolExecutionContext.builder("flow-a", "session-a")
                .runId("run-a").taskId("task-a").messageId("message-a").requestMode("plan")
                .allowedTools(Set.of("read_file")).build();

        ToolResult result = plugins.execute(context, new ToolCall("1", "session_context",
                mapper.createObjectNode().put("session_id", "forged").put("flow_id", "forged")));

        assertTrue(result.isSuccess(), result::getError);
        assertEquals("session-a", result.getResult().path("session_id").asText());
        assertEquals("run-a", result.getResult().path("run_id").asText());
        assertEquals("reasoning", result.getResult().path("flow_metadata").path("features").get(0).asText());
        assertEquals("kb", result.getResult().path("flow_metadata").path("knowledges").get(0).path("code").asText());
        assertTrue(result.getResult().path("capabilities").has("requested_skill_names"));
        assertFalse(result.getResult().toString().contains("secret"));
        assertFalse(result.getResult().path("flow_metadata").has("custom_fields"));
    }
}
