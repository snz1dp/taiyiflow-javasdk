package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Initial message sent to start a streaming run.
 * Must be sent within 10 seconds of WebSocket connection.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {

    // Mirrors backend RELATION_SESSIONS_MAX_COUNT (utils/constants.py): strip blanks, dedup, cap at 5.
    private static final int RELATION_SESSIONS_MAX_COUNT = 5;

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

    @JsonProperty("model")
    private String model;

    @JsonProperty("agent_auto_loop_enabled")
    private Boolean agentAutoLoopEnabled;

    @JsonProperty("agent_auto_loop_max_rounds")
    private Integer agentAutoLoopMaxRounds;

    @JsonProperty("image_generation_enabled")
    private Boolean imageGenerationEnabled;

    @JsonProperty("group_id")
    private String groupId;

    @JsonProperty("relation_sessions")
    private List<String> relationSessions;

    @JsonProperty("client_instructions")
    private JsonNode clientInstructions;

    @JsonProperty("agent_app_id")
    private String agentAppId;

    @JsonProperty("agent_app_owner_id")
    private String agentAppOwnerId;

    @JsonProperty("client_context_dir")
    private String clientContextDir;

    @JsonProperty("long_term_memory_enabled")
    private Boolean longTermMemoryEnabled;

    @JsonProperty("cross_session_memory_enabled")
    private Boolean crossSessionMemoryEnabled;

    @JsonProperty("runtime_strategy")
    private String runtimeStrategy;

    @JsonProperty("command_execution_target")
    private String commandExecutionTarget;

    @JsonProperty("max_iterations")
    private Integer maxIterations;

    @JsonProperty("agent_continue_windows")
    private Integer agentContinueWindows;

    @JsonProperty("agent_inputs")
    private JsonNode agentInputs;

    @JsonProperty("master_flow_id")
    private String masterFlowId;

    @JsonProperty("output_component")
    private String outputComponent;

    @JsonProperty("principal_id")
    private String principalId;

    @JsonProperty("actor_principal_id")
    private String actorPrincipalId;

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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Boolean getAgentAutoLoopEnabled() {
        return agentAutoLoopEnabled;
    }

    public void setAgentAutoLoopEnabled(Boolean agentAutoLoopEnabled) {
        this.agentAutoLoopEnabled = agentAutoLoopEnabled;
    }

    public Integer getAgentAutoLoopMaxRounds() {
        return agentAutoLoopMaxRounds;
    }

    public void setAgentAutoLoopMaxRounds(Integer agentAutoLoopMaxRounds) {
        this.agentAutoLoopMaxRounds = agentAutoLoopMaxRounds;
    }

    public Boolean getImageGenerationEnabled() {
        return imageGenerationEnabled;
    }

    public void setImageGenerationEnabled(Boolean imageGenerationEnabled) {
        this.imageGenerationEnabled = imageGenerationEnabled;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public List<String> getRelationSessions() {
        return relationSessions;
    }

    public void setRelationSessions(List<String> relationSessions) {
        if (relationSessions == null) {
            this.relationSessions = null;
            return;
        }
        List<String> normalized = new ArrayList<>();
        for (String sessionId : relationSessions) {
            String trimmed = sessionId == null ? "" : sessionId.strip();
            if (trimmed.isEmpty() || normalized.contains(trimmed)) {
                continue;
            }
            normalized.add(trimmed);
            if (normalized.size() >= RELATION_SESSIONS_MAX_COUNT) {
                break;
            }
        }
        // Empty collapses to null so NON_NULL omits the field instead of sending [].
        this.relationSessions = normalized.isEmpty() ? null : List.copyOf(normalized);
    }

    public JsonNode getClientInstructions() {
        return clientInstructions;
    }

    public void setClientInstructions(JsonNode clientInstructions) {
        this.clientInstructions = clientInstructions;
    }

    public String getAgentAppId() {
        return agentAppId;
    }

    public void setAgentAppId(String agentAppId) {
        this.agentAppId = agentAppId;
    }

    public String getAgentAppOwnerId() {
        return agentAppOwnerId;
    }

    public void setAgentAppOwnerId(String agentAppOwnerId) {
        this.agentAppOwnerId = agentAppOwnerId;
    }

    public String getClientContextDir() {
        return clientContextDir;
    }

    public void setClientContextDir(String clientContextDir) {
        this.clientContextDir = clientContextDir;
    }

    public Boolean getLongTermMemoryEnabled() {
        return longTermMemoryEnabled;
    }

    public void setLongTermMemoryEnabled(Boolean longTermMemoryEnabled) {
        this.longTermMemoryEnabled = longTermMemoryEnabled;
    }

    public Boolean getCrossSessionMemoryEnabled() {
        return crossSessionMemoryEnabled;
    }

    public void setCrossSessionMemoryEnabled(Boolean crossSessionMemoryEnabled) {
        this.crossSessionMemoryEnabled = crossSessionMemoryEnabled;
    }

    public String getRuntimeStrategy() {
        return runtimeStrategy;
    }

    public void setRuntimeStrategy(String runtimeStrategy) {
        this.runtimeStrategy = runtimeStrategy;
    }

    public String getCommandExecutionTarget() {
        return commandExecutionTarget;
    }

    public void setCommandExecutionTarget(String commandExecutionTarget) {
        this.commandExecutionTarget = commandExecutionTarget;
    }

    public Integer getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(Integer maxIterations) {
        this.maxIterations = maxIterations;
    }

    public Integer getAgentContinueWindows() {
        return agentContinueWindows;
    }

    public void setAgentContinueWindows(Integer agentContinueWindows) {
        this.agentContinueWindows = agentContinueWindows;
    }

    public JsonNode getAgentInputs() {
        return agentInputs;
    }

    public void setAgentInputs(JsonNode agentInputs) {
        this.agentInputs = agentInputs;
    }

    public String getMasterFlowId() {
        return masterFlowId;
    }

    public void setMasterFlowId(String masterFlowId) {
        this.masterFlowId = masterFlowId;
    }

    public String getOutputComponent() {
        return outputComponent;
    }

    public void setOutputComponent(String outputComponent) {
        this.outputComponent = outputComponent;
    }

    public String getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(String principalId) {
        this.principalId = principalId;
    }

    public String getActorPrincipalId() {
        return actorPrincipalId;
    }

    public void setActorPrincipalId(String actorPrincipalId) {
        this.actorPrincipalId = actorPrincipalId;
    }

    /** Copy request-option fields from a finalized first-frame snapshot (retry/repeat/restart). */
    public void copyOptionsFrom(ChatRequest source) {
        this.requestType = source.requestType;
        this.inputType = source.inputType;
        this.outputType = source.outputType;
        this.model = source.model;
        this.reasoning = source.reasoning;
        this.internetSearch = source.internetSearch;
        this.knowledgeScopes = source.knowledgeScopes == null ? null : source.knowledgeScopes.deepCopy();
        this.knowledgeDocs = source.knowledgeDocs == null ? null : source.knowledgeDocs.deepCopy();
        this.agentAutoLoopEnabled = source.agentAutoLoopEnabled;
        this.agentAutoLoopMaxRounds = source.agentAutoLoopMaxRounds;
        this.imageGenerationEnabled = source.imageGenerationEnabled;
        this.groupId = source.groupId;
        this.relationSessions = source.relationSessions == null ? null : List.copyOf(source.relationSessions);
        this.clientInstructions = source.clientInstructions == null ? null : source.clientInstructions.deepCopy();
        this.agentAppId = source.agentAppId;
        this.agentAppOwnerId = source.agentAppOwnerId;
        this.clientContextDir = source.clientContextDir;
        this.longTermMemoryEnabled = source.longTermMemoryEnabled;
        this.crossSessionMemoryEnabled = source.crossSessionMemoryEnabled;
        this.runtimeStrategy = source.runtimeStrategy;
        this.commandExecutionTarget = source.commandExecutionTarget;
        this.maxIterations = source.maxIterations;
        this.agentContinueWindows = source.agentContinueWindows;
        this.agentInputs = source.agentInputs == null ? null : source.agentInputs.deepCopy();
        this.masterFlowId = source.masterFlowId;
        this.outputComponent = source.outputComponent;
        this.principalId = source.principalId;
        this.actorPrincipalId = source.actorPrincipalId;
        this.clientType = source.clientType;
    }
}
