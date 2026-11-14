package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Initial message sent to start a streaming run.
 * Must be sent within 10 seconds of WebSocket connection.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {

    @JsonProperty("input_value")
    private String inputValue;

    @JsonProperty("input_type")
    private String inputType;

    @JsonProperty("output_type")
    private String outputType;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("tweaks")
    private Map<String, JsonNode> tweaks;

    @JsonProperty("run_id")
    private String runId;

    @JsonProperty("execution_mode")
    private String executionMode;

    @JsonProperty("async")
    private Boolean async;

    @JsonProperty("execution_attempt_id")
    private String executionAttemptId;

    @JsonProperty("after_event_id")
    private String afterEventId;

    @JsonProperty("subscription_id")
    private String subscriptionId;

    @JsonProperty("checkpoint_id")
    private String checkpointId;

    @JsonProperty("checkpoint_version")
    private Integer checkpointVersion;

    @JsonProperty("tool_results")
    private List<JsonNode> toolResults;

    @JsonProperty("submission_id")
    private String submissionId;

    @JsonProperty("message_id")
    private String messageId;

    @JsonProperty("files")
    private List<String> inputFiles;

    @JsonProperty("request_type")
    private String requestType;

    @JsonProperty("parent_id")
    private String parentId;

    @JsonProperty("reasoning")
    private Boolean reasoning;

    @JsonProperty("internet_search")
    private Boolean internetSearch;

    @JsonProperty("knowledge_scopes")
    private JsonNode knowledgeScopes;

    @JsonProperty("knowledge_docs")
    private JsonNode knowledgeDocs;

    @JsonProperty("requested_skill_names")
    private List<String> requestedSkillNames;

    @JsonProperty("client_skill_packages")
    private List<ClientSkillPackage> clientSkillPackages;

    @JsonProperty("available_tools")
    private List<JsonNode> availableTools;

    @JsonProperty("client_mcp_servers")
    private JsonNode clientMcpServers;

    @JsonProperty("client_id")
    private String clientId;

    @JsonProperty("device_id")
    private String deviceId;

    @JsonProperty("client_type")
    private String clientType;

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

    public String getInputType() {
        return inputType;
    }

    public void setInputType(String inputType) {
        this.inputType = inputType;
    }

    public String getOutputType() {
        return outputType;
    }

    public void setOutputType(String outputType) {
        this.outputType = outputType;
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

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public Boolean getAsync() {
        return async;
    }

    public void setAsync(Boolean async) {
        this.async = async;
    }

    public String getExecutionAttemptId() {
        return executionAttemptId;
    }

    public void setExecutionAttemptId(String executionAttemptId) {
        this.executionAttemptId = executionAttemptId;
    }

    public String getAfterEventId() {
        return afterEventId;
    }

    public void setAfterEventId(String afterEventId) {
        this.afterEventId = afterEventId;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(String subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public String getCheckpointId() {
        return checkpointId;
    }

    public void setCheckpointId(String checkpointId) {
        this.checkpointId = checkpointId;
    }

    public Integer getCheckpointVersion() {
        return checkpointVersion;
    }

    public void setCheckpointVersion(Integer checkpointVersion) {
        this.checkpointVersion = checkpointVersion;
    }

    public List<JsonNode> getToolResults() {
        return toolResults;
    }

    public void setToolResults(List<JsonNode> toolResults) {
        this.toolResults = toolResults;
    }

    public String getSubmissionId() {
        return submissionId;
    }

    public void setSubmissionId(String submissionId) {
        this.submissionId = submissionId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public List<String> getInputFiles() {
        return inputFiles;
    }

    public void setInputFiles(List<String> inputFiles) {
        this.inputFiles = inputFiles;
    }

    public String getRequestType() {
        return requestType;
    }

    public void setRequestType(String requestType) {
        this.requestType = requestType;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public Boolean getReasoning() {
        return reasoning;
    }

    public void setReasoning(Boolean reasoning) {
        this.reasoning = reasoning;
    }

    public Boolean getInternetSearch() {
        return internetSearch;
    }

    public void setInternetSearch(Boolean internetSearch) {
        this.internetSearch = internetSearch;
    }

    public JsonNode getKnowledgeScopes() {
        return knowledgeScopes;
    }

    public void setKnowledgeScopes(JsonNode knowledgeScopes) {
        this.knowledgeScopes = knowledgeScopes;
    }

    public JsonNode getKnowledgeDocs() {
        return knowledgeDocs;
    }

    public void setKnowledgeDocs(JsonNode knowledgeDocs) {
        this.knowledgeDocs = knowledgeDocs;
    }

    public List<String> getRequestedSkillNames() {
        return requestedSkillNames;
    }

    public void setRequestedSkillNames(List<String> requestedSkillNames) {
        this.requestedSkillNames = requestedSkillNames;
    }

    public List<ClientSkillPackage> getClientSkillPackages() {
        return clientSkillPackages;
    }

    public void setClientSkillPackages(List<ClientSkillPackage> clientSkillPackages) {
        this.clientSkillPackages = clientSkillPackages;
    }

    public List<JsonNode> getAvailableTools() {
        return availableTools;
    }

    public void setAvailableTools(List<JsonNode> availableTools) {
        this.availableTools = availableTools;
    }

    public JsonNode getClientMcpServers() {
        return clientMcpServers;
    }

    public void setClientMcpServers(JsonNode clientMcpServers) {
        this.clientMcpServers = clientMcpServers;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getClientType() {
        return clientType;
    }

    public void setClientType(String clientType) {
        this.clientType = clientType;
    }
}
