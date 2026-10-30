package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Represents a tool call requested by the agent.
 */
public class ToolCall {

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("arguments")
    private JsonNode arguments;

    @JsonProperty("type")
    private String type;

    public ToolCall() {
    }

    public ToolCall(String id, String name, JsonNode arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public JsonNode getArguments() {
        return arguments;
    }

    public void setArguments(JsonNode arguments) {
        this.arguments = arguments;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return "ToolCall{id='" + id + "', name='" + name + "', arguments=" + arguments + '}';
    }
}
