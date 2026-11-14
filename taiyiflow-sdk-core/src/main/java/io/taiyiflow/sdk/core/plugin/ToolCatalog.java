package io.taiyiflow.sdk.core.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.transport.envelope.FlowMetadata;
import io.taiyiflow.sdk.transport.envelope.ToolDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class ToolCatalog {
    private ToolCatalog() {
    }

    public static List<JsonNode> automatic(PluginManager plugins, FlowMetadata metadata, String requestMode,
                                           Set<String> allowedTools, PermissionPolicy permissionPolicy) {
        return plugins.getDefinitions().stream()
                .sorted(Comparator.comparing(ToolDefinition::getName))
                .filter(definition -> visible(definition, metadata, requestMode, allowedTools, permissionPolicy))
                .map(ToolDefinition::toJson).map(JsonNode.class::cast).toList();
    }

    public static List<JsonNode> explicit(PluginManager plugins, List<JsonNode> requested, FlowMetadata metadata,
                                          String requestMode, Set<String> allowedTools,
                                          PermissionPolicy permissionPolicy) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode candidate : requested == null ? List.<JsonNode>of() : requested) {
            if (candidate == null || (!candidate.isTextual()
                    && (!candidate.isObject() || !candidate.path("name").isTextual()))) {
                continue;
            }
            String name = candidate.isTextual() ? candidate.asText() : candidate.path("name").asText();
            ToolDefinition local = plugins.getDefinition(name);
            if (local == null) {
                if (!plugins.hasHandler(name)) {
                    result.add(candidate.deepCopy());
                }
                continue;
            }
            if (visible(local, metadata, requestMode, allowedTools, permissionPolicy)) {
                result.add(candidate.isTextual() ? candidate.deepCopy() : local.toJson());
            }
        }
        return List.copyOf(result);
    }

    private static boolean visible(ToolDefinition definition, FlowMetadata metadata, String requestMode,
                                   Set<String> allowedTools, PermissionPolicy permissionPolicy) {
        if (allowedTools != null && !allowedTools.contains(definition.getName())) {
            return false;
        }
        if (definition.getRequiredFeature() != null
                && (metadata == null || !metadata.supports(definition.getRequiredFeature()))) {
            return false;
        }
        boolean readOnlyRequest = "ask".equals(requestMode) || "plan".equals(requestMode);
        return !(readOnlyRequest || permissionPolicy == PermissionPolicy.READONLY)
                || !PermissionPolicy.isMutating(definition.getName());
    }
}
