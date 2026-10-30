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

    /**
     * Extract text content from a message event.
     */
    public String getMessageText() {
        if (data == null) {
            return null;
        }
        JsonNode text = data.get("text");
        if (text != null && text.isTextual()) {
            return text.asText();
        }
        JsonNode content = data.get("content");
        if (content != null && content.isTextual()) {
            return content.asText();
        }
        return null;
    }

    @Override
    public String toString() {
        return "StreamEvent{event='" + event + "', data=" + data + '}';
    }
}
