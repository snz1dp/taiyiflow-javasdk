package io.taiyiflow.sdk.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.core.skill.SkillSupplySnapshot;
import io.taiyiflow.sdk.core.subtask.SubtaskCapabilitySnapshot;
import io.taiyiflow.sdk.transport.envelope.AgentLoopControlResult;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import io.taiyiflow.sdk.transport.envelope.ResumableCheckpoint;
import io.taiyiflow.sdk.transport.envelope.StreamEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

public class RunContext {

    private String runId;
    private String checkpointId;
    private Integer checkpointVersion;
    private String afterEventId;
    private String executionAttemptId;
    private String lastInputValue;
    private Map<String, JsonNode> lastTweaks = Map.of();
    private List<String> lastInputFiles = List.of();
    private String requestType;
    private SubtaskCapabilitySnapshot capabilities = SubtaskCapabilitySnapshot.empty();
    private List<JsonNode> availableTools;
    private SkillSupplySnapshot skillSupply = SkillSupplySnapshot.empty();
    private long recoveryGeneration;
    private boolean persisted;

    public synchronized void begin(ChatRequest request) {
        runId = request.getRunId();
        checkpointId = request.getCheckpointId();
        checkpointVersion = request.getCheckpointVersion();
        afterEventId = request.getAfterEventId();
        executionAttemptId = request.getExecutionAttemptId();
        lastInputValue = request.getInputValue();
        lastTweaks = copyTweaks(request.getTweaks());
        lastInputFiles = request.getInputFiles() == null ? List.of() : List.copyOf(request.getInputFiles());
        requestType = request.getRequestType();
        capabilities = SubtaskCapabilitySnapshot.fromChatRequest(request);
        availableTools = request.getAvailableTools() == null ? null : request.getAvailableTools().stream()
                .<JsonNode>map(JsonNode::deepCopy).toList();
        skillSupply = new SkillSupplySnapshot(request.getClientSkillPackages(), request.getRequestedSkillNames(), List.of());
        persisted = false;
    }

    public synchronized void update(StreamEvent event) {
        JsonNode data = event == null ? null : event.getData();
        if (data == null || !data.isObject()) {
            return;
        }
        String eventRunId = text(data, "run_id");
        if (runId != null && eventRunId != null && !runId.equals(eventRunId)) {
            return;
        }
        if (eventRunId != null) {
            runId = eventRunId;
        }
        String attemptId = text(data, "execution_attempt_id");
        if (attemptId != null) {
            executionAttemptId = attemptId;
        }
        String eventId = text(data, "event_id");
        if (eventId != null) {
            afterEventId = eventId;
            persisted = true;
        }
        String eventCheckpointId = text(data, "checkpoint_id");
        if (eventCheckpointId != null) {
            checkpointId = eventCheckpointId;
        }
        JsonNode version = data.get("checkpoint_version");
        if (version != null && version.canConvertToInt()) {
            checkpointVersion = version.intValue();
        }
    }

    public synchronized boolean update(AgentLoopControlResult result, long expectedGeneration) {
        if (recoveryGeneration != expectedGeneration) {
            return false;
        }
        if (result.getRunId() != null) {
            runId = result.getRunId();
        }
        if (result.getCheckpointId() != null) {
            checkpointId = result.getCheckpointId();
        }
        return true;
    }

    public synchronized boolean adoptExecution(String runId, String executionAttemptId, long expectedGeneration) {
        if (recoveryGeneration != expectedGeneration || runId == null
                || (this.runId != null && !this.runId.equals(runId))) {
            return false;
        }
        this.runId = runId;
        if (executionAttemptId != null) {
            this.executionAttemptId = executionAttemptId;
        }
        return true;
    }

    public synchronized boolean update(ResumableCheckpoint checkpoint, long expectedGeneration) {
        if (recoveryGeneration != expectedGeneration) {
            return false;
        }
        runId = checkpoint.getRunId();
        checkpointId = checkpoint.getId();
        checkpointVersion = checkpoint.getVersion();
        persisted = true;
        return true;
    }

    public synchronized long nextRecoveryGeneration() {
        return ++recoveryGeneration;
    }

    public synchronized String getRunId() {
        return runId;
    }

    public synchronized String getCheckpointId() {
        return checkpointId;
    }

    public synchronized Integer getCheckpointVersion() {
        return checkpointVersion;
    }

    public synchronized String getAfterEventId() {
        return afterEventId;
    }

    public synchronized String getExecutionAttemptId() {
        return executionAttemptId;
    }

    public synchronized String getLastInputValue() {
        return lastInputValue;
    }

    public synchronized Map<String, JsonNode> getLastTweaks() {
        return copyTweaks(lastTweaks);
    }

    public synchronized List<String> getLastInputFiles() {
        return lastInputFiles;
    }

    public synchronized String getRequestType() {
        return requestType;
    }

    public synchronized SubtaskCapabilitySnapshot getCapabilities() {
        return capabilities;
    }

    public synchronized List<JsonNode> getAvailableTools() {
        return availableTools == null ? null : availableTools.stream().<JsonNode>map(JsonNode::deepCopy).toList();
    }

    public synchronized SkillSupplySnapshot getSkillSupply() {
        return skillSupply;
    }

    public synchronized long getRecoveryGeneration() {
        return recoveryGeneration;
    }

    public synchronized boolean isPersisted() {
        return persisted;
    }

    private static Map<String, JsonNode> copyTweaks(Map<String, JsonNode> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, value == null ? null : value.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }

    private static String text(JsonNode data, String field) {
        JsonNode value = data.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
