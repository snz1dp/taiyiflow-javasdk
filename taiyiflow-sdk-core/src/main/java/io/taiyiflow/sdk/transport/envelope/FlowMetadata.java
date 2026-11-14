package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FlowMetadata {
    private static final Set<String> KNOWN_FIELDS = Set.of("id", "owner_appid", "name", "description",
            "enabled", "is_chat", "max_input_tokens", "welcome_message", "favicon", "logo", "avatar",
            "ai_name", "features", "questions", "knowledges", "sub_head", "custom_fields", "shadow_flows");

    private final JsonNode raw;
    private final String id;
    private final String ownerAppId;
    private final String name;
    private final String description;
    private final Boolean enabled;
    private final Boolean chat;
    private final Integer maxInputTokens;
    private final String welcomeMessage;
    private final String favicon;
    private final String logo;
    private final String avatar;
    private final String aiName;
    private final List<String> features;
    private final List<String> questions;
    private final List<Knowledge> knowledges;
    private final String subHead;
    private final JsonNode customFields;
    private final List<ShadowFlow> shadowFlows;
    private final Map<String, JsonNode> additionalProperties;

    public FlowMetadata(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new IllegalArgumentException("Flow metadata must be a JSON object");
        }
        this.raw = raw.deepCopy();
        id = text("id");
        ownerAppId = text("owner_appid");
        name = text("name");
        description = text("description");
        enabled = bool("enabled");
        chat = bool("is_chat");
        maxInputTokens = raw.path("max_input_tokens").canConvertToInt() ? raw.path("max_input_tokens").intValue() : null;
        welcomeMessage = text("welcome_message");
        favicon = text("favicon");
        logo = text("logo");
        avatar = text("avatar");
        aiName = text("ai_name");
        features = strings(raw.get("features"));
        questions = strings(raw.get("questions"));
        knowledges = raw.path("knowledges").isArray() ? JsonNodes.stream(raw.path("knowledges"))
                .filter(JsonNode::isObject).map(Knowledge::new).toList() : List.of();
        subHead = text("sub_head");
        customFields = raw.has("custom_fields") && !raw.get("custom_fields").isNull()
                ? raw.get("custom_fields").deepCopy() : null;
        shadowFlows = raw.path("shadow_flows").isArray() ? JsonNodes.stream(raw.path("shadow_flows"))
                .map(ShadowFlow::new).toList() : List.of();
        Map<String, JsonNode> additional = new LinkedHashMap<>();
        raw.fields().forEachRemaining(entry -> {
            if (!KNOWN_FIELDS.contains(entry.getKey())) {
                additional.put(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        additionalProperties = Collections.unmodifiableMap(additional);
    }

    public boolean supports(String feature) {
        return feature != null && features.stream().anyMatch(feature::equals);
    }

    public boolean supportsReasoning() { return supports("reasoning"); }
    public boolean supportsInternetSearch() { return supports("internet_search"); }
    public boolean supportsKnowledgeSearch() { return supports("knowledge_search"); }
    public boolean supportsImageGeneration() { return supports("image_generation"); }
    public boolean supportsMcpTools() { return supports("support_mcp_tools"); }
    public boolean supportsAgentApp() { return supports("support_agent_app"); }

    public String getId() { return id; }
    public String getOwnerAppId() { return ownerAppId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Boolean getEnabled() { return enabled; }
    public Boolean isChat() { return chat; }
    public Integer getMaxInputTokens() { return maxInputTokens; }
    public String getWelcomeMessage() { return welcomeMessage; }
    public String getFavicon() { return favicon; }
    public String getLogo() { return logo; }
    public String getAvatar() { return avatar; }
    public String getAiName() { return aiName; }
    public List<String> getFeatures() { return features; }
    public List<String> getQuestions() { return questions; }
    public List<Knowledge> getKnowledges() { return knowledges; }
    public String getSubHead() { return subHead; }
    public JsonNode getCustomFields() { return customFields == null ? null : customFields.deepCopy(); }
    public List<ShadowFlow> getShadowFlows() { return shadowFlows; }
    public Map<String, JsonNode> getAdditionalProperties() {
        Map<String, JsonNode> copy = new LinkedHashMap<>();
        additionalProperties.forEach((key, value) -> copy.put(key, value.deepCopy()));
        return Collections.unmodifiableMap(copy);
    }
    public JsonNode raw() { return raw.deepCopy(); }

    private String text(String field) {
        JsonNode value = raw.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Boolean bool(String field) {
        JsonNode value = raw.get(field);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    private static List<String> strings(JsonNode values) {
        return values != null && values.isArray() ? JsonNodes.stream(values)
                .filter(JsonNode::isTextual).map(JsonNode::asText).toList() : List.of();
    }

    public record Knowledge(String code, String name, String type, JsonNode raw) {
        public Knowledge(JsonNode raw) {
            this(raw.path("code").asText(null), raw.path("name").asText(null),
                    raw.path("type").asText(null), raw.deepCopy());
        }
        @Override public JsonNode raw() { return raw.deepCopy(); }
    }

    public record ShadowFlow(JsonNode raw) {
        public ShadowFlow { raw = raw == null ? null : raw.deepCopy(); }
        @Override public JsonNode raw() { return raw == null ? null : raw.deepCopy(); }
    }
}
