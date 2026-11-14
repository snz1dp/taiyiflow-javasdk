package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

public final class ToolDefinition {
    private final String name;
    private final String description;
    private final JsonNode inputSchema;
    private final String requiredFeature;

    public ToolDefinition(String name, String description, JsonNode inputSchema) {
        this(name, description, inputSchema, null);
    }

    public ToolDefinition(String name, String description, JsonNode inputSchema, String requiredFeature) {
        if (name == null || name.isBlank() || description == null || description.isBlank()
                || inputSchema == null || !inputSchema.isObject()) {
            throw new IllegalArgumentException("Tool name, description and object input_schema are required");
        }
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema.deepCopy();
        this.requiredFeature = requiredFeature;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    @JsonProperty("input_schema")
    public JsonNode getInputSchema() {
        return inputSchema.deepCopy();
    }

    @JsonIgnore
    public String getRequiredFeature() {
        return requiredFeature;
    }

    public ObjectNode toJson() {
        ObjectNode json = JsonNodeFactory.instance.objectNode();
        json.put("name", name);
        json.put("description", description);
        json.set("input_schema", inputSchema.deepCopy());
        return json;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ToolDefinition that && name.equals(that.name)
                && description.equals(that.description) && inputSchema.equals(that.inputSchema)
                && Objects.equals(requiredFeature, that.requiredFeature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, description, inputSchema, requiredFeature);
    }
}
