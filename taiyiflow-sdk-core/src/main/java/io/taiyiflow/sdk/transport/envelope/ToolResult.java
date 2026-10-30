package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Result of a tool call execution.
 */
public class ToolResult {

    @JsonProperty("tool_call_id")
    private String toolCallId;

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("result")
    private JsonNode result;

    @JsonProperty("error")
    private String error;

    public ToolResult() {
    }

    public static ToolResult success(String toolCallId, JsonNode result) {
        ToolResult r = new ToolResult();
        r.toolCallId = toolCallId;
        r.success = true;
        r.result = result;
        return r;
    }

    public static ToolResult error(String toolCallId, String error) {
        ToolResult r = new ToolResult();
        r.toolCallId = toolCallId;
        r.success = false;
        r.error = error;
        return r;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public JsonNode getResult() {
        return result;
    }

    public void setResult(JsonNode result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    @Override
    public String toString() {
        return "ToolResult{toolCallId='" + toolCallId + "', success=" + success +
               ", result=" + result + ", error='" + error + "'}";
    }
}
