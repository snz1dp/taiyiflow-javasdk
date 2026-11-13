package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumableCheckpoint {

    @JsonProperty("id")
    private String id;

    @JsonProperty("flow_id")
    private String flowId;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("origin_client_id")
    private String originClientId;

    @JsonProperty("origin_device_id")
    private String originDeviceId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("version")
    private int version;

    @JsonProperty("resume_stage")
    private String resumeStage;

    @JsonProperty("pause_reason")
    private String pauseReason;

    @JsonProperty("requires_user_resume")
    private boolean requiresUserResume;

    @JsonProperty("waiting_call_ids")
    private List<String> waitingCallIds = List.of();

    @JsonProperty("pending_call_ids")
    private List<String> pendingCallIds = List.of();

    @JsonProperty("uncertain_call_ids")
    private List<String> uncertainCallIds = List.of();

    @JsonProperty("last_submission_id")
    private String lastSubmissionId;

    @JsonProperty("last_submission_hash")
    private String lastSubmissionHash;

    @JsonProperty("updated_at")
    private String updatedAt;

    @JsonProperty("expires_at")
    private String expiresAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFlowId() {
        return flowId;
    }

    public void setFlowId(String flowId) {
        this.flowId = flowId;
    }

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

    public String getOriginClientId() {
        return originClientId;
    }

    public void setOriginClientId(String originClientId) {
        this.originClientId = originClientId;
    }

    public String getOriginDeviceId() {
        return originDeviceId;
    }

    public void setOriginDeviceId(String originDeviceId) {
        this.originDeviceId = originDeviceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getResumeStage() {
        return resumeStage;
    }

    public void setResumeStage(String resumeStage) {
        this.resumeStage = resumeStage;
    }

    public String getPauseReason() {
        return pauseReason;
    }

    public void setPauseReason(String pauseReason) {
        this.pauseReason = pauseReason;
    }

    public boolean isRequiresUserResume() {
        return requiresUserResume;
    }

    public void setRequiresUserResume(boolean requiresUserResume) {
        this.requiresUserResume = requiresUserResume;
    }

    public List<String> getWaitingCallIds() {
        return waitingCallIds == null ? List.of() : List.copyOf(waitingCallIds);
    }

    public void setWaitingCallIds(List<String> waitingCallIds) {
        this.waitingCallIds = waitingCallIds == null ? List.of() : List.copyOf(waitingCallIds);
    }

    public List<String> getPendingCallIds() {
        return pendingCallIds == null ? List.of() : List.copyOf(pendingCallIds);
    }

    public void setPendingCallIds(List<String> pendingCallIds) {
        this.pendingCallIds = pendingCallIds == null ? List.of() : List.copyOf(pendingCallIds);
    }

    public List<String> getUncertainCallIds() {
        return uncertainCallIds == null ? List.of() : List.copyOf(uncertainCallIds);
    }

    public void setUncertainCallIds(List<String> uncertainCallIds) {
        this.uncertainCallIds = uncertainCallIds == null ? List.of() : List.copyOf(uncertainCallIds);
    }

    public String getLastSubmissionId() {
        return lastSubmissionId;
    }

    public void setLastSubmissionId(String lastSubmissionId) {
        this.lastSubmissionId = lastSubmissionId;
    }

    public String getLastSubmissionHash() {
        return lastSubmissionHash;
    }

    public void setLastSubmissionHash(String lastSubmissionHash) {
        this.lastSubmissionHash = lastSubmissionHash;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean hasUncertainSideEffects() {
        return !getPendingCallIds().isEmpty() || !getUncertainCallIds().isEmpty();
    }
}
