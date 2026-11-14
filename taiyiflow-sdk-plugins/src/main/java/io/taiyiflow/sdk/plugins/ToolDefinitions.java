package io.taiyiflow.sdk.plugins;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.transport.envelope.ToolDefinition;

public final class ToolDefinitions {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolDefinitions() {
    }

    public static ToolDefinition tool(String name, String description, String propertiesJson,
                                      String... required) {
        return create(name, description, propertiesJson, null, required);
    }

    public static ToolDefinition featureTool(String name, String description, String feature,
                                             String propertiesJson, String... required) {
        return create(name, description, propertiesJson, feature, required);
    }

    private static ToolDefinition create(String name, String description, String propertiesJson,
                                         String feature, String... required) {
        try {
            JsonNode properties = MAPPER.readTree(propertiesJson);
            if (!properties.isObject()) {
                throw new IllegalArgumentException("Tool properties must be an object");
            }
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", properties);
            schema.put("additionalProperties", false);
            if (required.length > 0) {
                var requiredNode = schema.putArray("required");
                for (String requiredName : required) {
                    requiredNode.add(requiredName);
                }
            }
            return new ToolDefinition(name, description, schema, feature);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid schema for tool " + name, exception);
        }
    }
}
