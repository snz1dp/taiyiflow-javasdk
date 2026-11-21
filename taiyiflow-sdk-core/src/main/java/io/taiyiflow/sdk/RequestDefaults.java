package io.taiyiflow.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Agent-level defaults applied to every new start request when the caller
 * leaves the corresponding field unset. Explicit per-request values and
 * {@link FlowAdapter} customizations always win.
 */
public final class RequestDefaults {

    public static final int MAX_RELATION_SESSIONS = 5;

    public enum ImageGeneration { AUTO, ON, OFF }

    private final String requestType;
    private final String inputType;
    private final String outputType;
    private final String model;
    private final Boolean reasoning;
    private final Boolean internetSearch;
    private final JsonNode knowledgeScopes;
    private final JsonNode knowledgeDocs;
    private final boolean autoLoopEnabled;
    private final Integer autoLoopMaxRounds;
    private final ImageGeneration imageGeneration;
    private final String groupId;
    private final List<String> relationSessions;
    private final String agentAppId;
    private final String agentAppOwnerId;
    private final String clientContextDir;
    private final Boolean longTermMemoryEnabled;
    private final Boolean crossSessionMemoryEnabled;

    private RequestDefaults(Builder builder) {
        this.requestType = builder.requestType;
        this.inputType = builder.inputType;
        this.outputType = builder.outputType;
        this.model = builder.model;
        this.reasoning = builder.reasoning;
        this.internetSearch = builder.internetSearch;
        this.knowledgeScopes = builder.knowledgeScopes == null ? null : builder.knowledgeScopes.deepCopy();
        this.knowledgeDocs = builder.knowledgeDocs == null ? null : builder.knowledgeDocs.deepCopy();
        this.autoLoopEnabled = builder.autoLoopEnabled;
        this.autoLoopMaxRounds = builder.autoLoopMaxRounds;
        this.imageGeneration = builder.imageGeneration;
        this.groupId = builder.groupId;
        this.relationSessions = List.copyOf(builder.relationSessions);
        this.agentAppId = builder.agentAppId;
        this.agentAppOwnerId = builder.agentAppOwnerId;
        this.clientContextDir = builder.clientContextDir;
        this.longTermMemoryEnabled = builder.longTermMemoryEnabled;
        this.crossSessionMemoryEnabled = builder.crossSessionMemoryEnabled;
    }

    public static RequestDefaults defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fill only fields the caller left unset; never override explicit values. */
    public void applyTo(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.getRequestType() == null && requestType != null) {
            request.setRequestType(requestType);
        }
        if (request.getInputType() == null) {
            request.setInputType(inputType);
        }
        if (request.getOutputType() == null) {
            request.setOutputType(outputType);
        }
        if (request.getModel() == null && model != null) {
            request.setModel(model);
        }
        if (request.getReasoning() == null && reasoning != null) {
            request.setReasoning(reasoning);
        }
        if (request.getInternetSearch() == null && internetSearch != null) {
            request.setInternetSearch(internetSearch);
        }
        if (request.getKnowledgeScopes() == null && knowledgeScopes != null) {
            request.setKnowledgeScopes(knowledgeScopes.deepCopy());
        }
        if (request.getKnowledgeDocs() == null && knowledgeDocs != null) {
            request.setKnowledgeDocs(knowledgeDocs.deepCopy());
        }
        if (request.getAgentAutoLoopEnabled() == null) {
            request.setAgentAutoLoopEnabled(autoLoopEnabled);
        }
        if (request.getAgentAutoLoopMaxRounds() == null && autoLoopMaxRounds != null) {
            request.setAgentAutoLoopMaxRounds(autoLoopMaxRounds);
        }
        if (request.getImageGenerationEnabled() == null && imageGeneration != ImageGeneration.AUTO) {
            request.setImageGenerationEnabled(imageGeneration == ImageGeneration.ON);
        }
        if (request.getGroupId() == null && groupId != null) {
            request.setGroupId(groupId);
        }
        if (request.getRelationSessions() == null && !relationSessions.isEmpty()) {
            request.setRelationSessions(relationSessions);
        }
        if (request.getAgentAppId() == null && agentAppId != null) {
            request.setAgentAppId(agentAppId);
        }
        if (request.getAgentAppOwnerId() == null && agentAppOwnerId != null) {
            request.setAgentAppOwnerId(agentAppOwnerId);
        }
        if (request.getClientContextDir() == null && clientContextDir != null) {
            request.setClientContextDir(clientContextDir);
        }
        if (request.getLongTermMemoryEnabled() == null && longTermMemoryEnabled != null) {
            request.setLongTermMemoryEnabled(longTermMemoryEnabled);
        }
        if (request.getCrossSessionMemoryEnabled() == null && crossSessionMemoryEnabled != null) {
            request.setCrossSessionMemoryEnabled(crossSessionMemoryEnabled);
        }
    }

    public String getRequestType() {
        return requestType;
    }

    public String getInputType() {
        return inputType;
    }

    public String getOutputType() {
        return outputType;
    }

    public String getModel() {
        return model;
    }

    public Boolean getReasoning() {
        return reasoning;
    }

    public Boolean getInternetSearch() {
        return internetSearch;
    }

    public JsonNode getKnowledgeScopes() {
        return knowledgeScopes == null ? null : knowledgeScopes.deepCopy();
    }

    public JsonNode getKnowledgeDocs() {
        return knowledgeDocs == null ? null : knowledgeDocs.deepCopy();
    }

    public boolean isAutoLoopEnabled() {
        return autoLoopEnabled;
    }

    public Integer getAutoLoopMaxRounds() {
        return autoLoopMaxRounds;
    }

    public ImageGeneration getImageGeneration() {
        return imageGeneration;
    }

    public String getGroupId() {
        return groupId;
    }

    public List<String> getRelationSessions() {
        return relationSessions;
    }

    public String getAgentAppId() {
        return agentAppId;
    }

    public String getAgentAppOwnerId() {
        return agentAppOwnerId;
    }

    public String getClientContextDir() {
        return clientContextDir;
    }

    public Boolean getLongTermMemoryEnabled() {
        return longTermMemoryEnabled;
    }

    public Boolean getCrossSessionMemoryEnabled() {
        return crossSessionMemoryEnabled;
    }

    public static final class Builder {
        private String requestType;
        private String inputType = "chat";
        private String outputType = "chat";
        private String model;
        private Boolean reasoning;
        private Boolean internetSearch;
        private JsonNode knowledgeScopes;
        private JsonNode knowledgeDocs;
        private boolean autoLoopEnabled = false;
        private Integer autoLoopMaxRounds;
        private ImageGeneration imageGeneration = ImageGeneration.AUTO;
        private String groupId;
        private List<String> relationSessions = List.of();
        private String agentAppId;
        private String agentAppOwnerId;
        private String clientContextDir;
        private Boolean longTermMemoryEnabled;
        private Boolean crossSessionMemoryEnabled;

        public Builder requestType(String requestType) {
            this.requestType = requestType;
            return this;
        }

        public Builder inputType(String inputType) {
            this.inputType = requireText(inputType, "inputType");
            return this;
        }

        public Builder outputType(String outputType) {
            this.outputType = requireText(outputType, "outputType");
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder reasoning(Boolean reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        public Builder internetSearch(Boolean internetSearch) {
            this.internetSearch = internetSearch;
            return this;
        }

        public Builder knowledgeScopes(JsonNode knowledgeScopes) {
            this.knowledgeScopes = knowledgeScopes;
            return this;
        }

        public Builder knowledgeDocs(JsonNode knowledgeDocs) {
            this.knowledgeDocs = knowledgeDocs;
            return this;
        }

        public Builder autoLoopEnabled(boolean autoLoopEnabled) {
            this.autoLoopEnabled = autoLoopEnabled;
            return this;
        }

        public Builder autoLoopMaxRounds(Integer autoLoopMaxRounds) {
            if (autoLoopMaxRounds != null && autoLoopMaxRounds < 0) {
                throw new IllegalArgumentException("autoLoopMaxRounds must be >= 0");
            }
            this.autoLoopMaxRounds = autoLoopMaxRounds;
            return this;
        }

        public Builder imageGeneration(ImageGeneration imageGeneration) {
            this.imageGeneration = Objects.requireNonNull(imageGeneration, "imageGeneration");
            return this;
        }

        public Builder groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder relationSessions(List<String> relationSessions) {
            if (relationSessions == null) {
                this.relationSessions = List.of();
                return this;
            }
            List<String> normalized = new ArrayList<>();
            for (String sessionId : relationSessions) {
                if (sessionId == null || sessionId.isBlank()) {
                    throw new IllegalArgumentException("relationSessions entries must not be blank");
                }
                String trimmed = sessionId.trim();
                if (!normalized.contains(trimmed)) {
                    normalized.add(trimmed);
                }
            }
            if (normalized.size() > MAX_RELATION_SESSIONS) {
                throw new IllegalArgumentException(
                        "relationSessions supports at most " + MAX_RELATION_SESSIONS + " sessions");
            }
            this.relationSessions = List.copyOf(normalized);
            return this;
        }

        public Builder agentAppId(String agentAppId) {
            this.agentAppId = agentAppId;
            return this;
        }

        public Builder agentAppOwnerId(String agentAppOwnerId) {
            this.agentAppOwnerId = agentAppOwnerId;
            return this;
        }

        public Builder clientContextDir(String clientContextDir) {
            this.clientContextDir = clientContextDir;
            return this;
        }

        public Builder longTermMemoryEnabled(Boolean longTermMemoryEnabled) {
            this.longTermMemoryEnabled = longTermMemoryEnabled;
            return this;
        }

        public Builder crossSessionMemoryEnabled(Boolean crossSessionMemoryEnabled) {
            this.crossSessionMemoryEnabled = crossSessionMemoryEnabled;
            return this;
        }

        public RequestDefaults build() {
            return new RequestDefaults(this);
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}