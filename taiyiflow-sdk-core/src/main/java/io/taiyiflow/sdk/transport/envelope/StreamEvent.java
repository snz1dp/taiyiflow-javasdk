package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Stream event received from the agent.
 * Events are wrapped in a JSON envelope: {"event": "...", "data": {...}}
 */
public class StreamEvent {

    @JsonProperty("event")
    private String event;

    @JsonProperty("data")
    private JsonNode data;

    public StreamEvent() {
    }

    public StreamEvent(String event, JsonNode data) {
        this.event = event;
        this.data = data;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public JsonNode getData() {
        return data;
    }

    public void setData(JsonNode data) {
        this.data = data;
    }

    public boolean isMessage() {
        return EventConstants.MESSAGE.equals(event);
    }

    public boolean isHeartbeat() {
        return EventConstants.HEARTBEAT.equals(event);
    }

    public boolean isError() {
        return EventConstants.ERROR.equals(event);
    }

    public boolean isClose() {
        return EventConstants.CLOSE.equals(event);
    }

    public boolean isCustom() {
        return EventConstants.CUSTOM.equals(event);
    }

    public boolean isBytes() {
        return EventConstants.BYTES.equals(event);
    }

    public boolean isToolCall() {
        return EventConstants.TOOL_CALL.equals(event);
    }

    public boolean isFileWritten() {
        return EventConstants.FILE_WRITTEN.equals(event);
    }

    public boolean isClientTool() {
        return EventConstants.CLIENT_TOOL.equals(event);
    }

    public boolean isMcpTool() {
        return EventConstants.MCP_TOOL.equals(event);
    }

    public boolean isBuiltIn() {
        return EventConstants.BUILT_IN.equals(event);
    }

    public String getMessageText() {
        if (data == null) {
            return null;
        }
        JsonNode chunk = data.get("chunk");
        return chunk != null && chunk.isTextual() ? chunk.asText() : null;
    }

    public String getErrorMessage() {
        if (data == null) {
            return null;
        }
        JsonNode error = data.get("error");
        if (error == null || error.isNull()) {
            return null;
        }
        return error.isTextual() ? error.asText() : error.toString();
    }

    /** Machine-readable error code on an {@code error} event ({@code data.type}), e.g. license_expired. */
    public String getErrorType() {
        if (data == null) {
            return null;
        }
        JsonNode type = data.get("type");
        return type != null && type.isTextual() ? type.asText() : null;
    }

    @Override
    public String toString() {
        return "StreamEvent{event='" + event + "', data=" + data + '}';
    }
}
