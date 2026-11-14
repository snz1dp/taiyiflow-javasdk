package io.taiyiflow.sdk.plugins.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.FlowMetadata;
import io.taiyiflow.sdk.transport.envelope.ToolResult;

import static io.taiyiflow.sdk.plugins.ToolDefinitions.tool;

public final class SessionContextPlugin {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MetadataProvider metadataProvider;

    private SessionContextPlugin(MetadataProvider metadataProvider) {
        this.metadataProvider = metadataProvider;
    }

    public static void register(PluginManager plugins, MetadataProvider metadataProvider) {
        SessionContextPlugin plugin = new SessionContextPlugin(metadataProvider);
        plugins.register(tool("session_context",
                "Read trusted session identity, subtask capabilities, and current Flow metadata.", "{}"),
                (PluginManager.ContextualToolHandler) (context, call) -> {
                    try {
                        ObjectNode result = MAPPER.createObjectNode();
                        putText(result, "flow_id", context.getFlowId());
                        putText(result, "session_id", context.getSessionId());
                        putText(result, "run_id", context.getRunId());
                        putText(result, "task_id", context.getTaskId());
                        putText(result, "message_id", context.getMessageId());
                        result.put("request_mode", context.getRequestMode());
                        result.set("capabilities", context.getCapabilities().toFeatures());
                        result.set("allowed_tools", context.getCapabilities().availableTools());

                        FlowMetadata metadata = plugin.metadataProvider.metadata();
                        ObjectNode flow = result.putObject("flow_metadata");
                        putText(flow, "id", metadata.getId());
                        putText(flow, "owner_appid", metadata.getOwnerAppId());
                        putText(flow, "name", metadata.getName());
                        putText(flow, "description", metadata.getDescription());
                        ArrayNode features = flow.putArray("features");
                        metadata.getFeatures().forEach(features::add);
                        ArrayNode knowledges = flow.putArray("knowledges");
                        metadata.getKnowledges().forEach(knowledge -> {
                            ObjectNode item = knowledges.addObject();
                            putText(item, "code", knowledge.code());
                            putText(item, "name", knowledge.name());
                            putText(item, "type", knowledge.type());
                        });
                        return ToolResult.success(call.getId(), result);
                    } catch (Exception exception) {
                        return ToolResult.error(call.getId(), exception.getMessage());
                    }
                });
    }

    private static void putText(ObjectNode target, String field, String value) {
        if (value != null) target.put(field, value);
        else target.putNull(field);
    }

    @FunctionalInterface
    public interface MetadataProvider {
        FlowMetadata metadata() throws Exception;
    }
}
