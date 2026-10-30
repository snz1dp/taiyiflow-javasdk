package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Initial message sent to start a streaming run.
 * Must be sent within 10 seconds of WebSocket connection.
 */
public class ChatRequest {

    @JsonProperty("input_value")
    private String inputValue;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("tweaks")
    private Map<String, JsonNode> tweaks;

    public ChatRequest() {
    }

    public ChatRequest(String inputValue, String sessionId) {
        this.inputValue = inputValue;
        this.sessionId = sessionId;
    }

    public String getInputValue() {
        return inputValue;
    }

    public void setInputValue(String inputValue) {
        this.inputValue = inputValue;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Map<String, JsonNode> getTweaks() {
        return tweaks;
    }

    public void setTweaks(Map<String, JsonNode> tweaks) {
        this.tweaks = tweaks;
    }
}
