package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentLoopControlResult {

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("checkpoint_id")
    private String checkpointId;

    @JsonProperty("subtask_cancel_requested")
    private int subtaskCancelRequested;

    @JsonProperty("subtask_cancel_pending")
    private int subtaskCancelPending;

    @JsonProperty("subtask_cancel_failed")
    private boolean subtaskCancelFailed;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getCheckpointId() {
        return checkpointId;
    }

    public void setCheckpointId(String checkpointId) {
        this.checkpointId = checkpointId;
    }

    public int getSubtaskCancelRequested() {
        return subtaskCancelRequested;
    }

    public void setSubtaskCancelRequested(int subtaskCancelRequested) {
        this.subtaskCancelRequested = subtaskCancelRequested;
    }

    public int getSubtaskCancelPending() {
        return subtaskCancelPending;
    }

    public void setSubtaskCancelPending(int subtaskCancelPending) {
        this.subtaskCancelPending = subtaskCancelPending;
    }

    public boolean isSubtaskCancelFailed() {
        return subtaskCancelFailed;
    }

    public void setSubtaskCancelFailed(boolean subtaskCancelFailed) {
        this.subtaskCancelFailed = subtaskCancelFailed;
    }
}
