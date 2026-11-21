package io.taiyiflow.sdk.core.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import io.taiyiflow.sdk.transport.envelope.ClientSkillPackage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SubtaskCapabilitySnapshot {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Boolean reasoning;
    private final Boolean internetSearch;
    private final JsonNode knowledgeScopes;
    private final JsonNode knowledgeDocs;
    private final List<String> requestedSkillNames;
    private final List<ClientSkillPackage> clientSkillPackages;
    private final List<String> mcpServerNames;
    private final Set<String> allowedTools;
    private final String model;

    public SubtaskCapabilitySnapshot(Boolean reasoning, Boolean internetSearch, JsonNode knowledgeScopes,
                                     JsonNode knowledgeDocs, List<String> requestedSkillNames,
                                     List<String> mcpServerNames, Set<String> allowedTools) {
        this(reasoning, internetSearch, knowledgeScopes, knowledgeDocs, requestedSkillNames,
                List.of(), mcpServerNames, allowedTools);
    }

    public SubtaskCapabilitySnapshot(Boolean reasoning, Boolean internetSearch, JsonNode knowledgeScopes,
                                     JsonNode knowledgeDocs, List<String> requestedSkillNames,
                                     List<ClientSkillPackage> clientSkillPackages,
                                     List<String> mcpServerNames, Set<String> allowedTools) {
        this(reasoning, internetSearch, knowledgeScopes, knowledgeDocs, requestedSkillNames,
                clientSkillPackages, mcpServerNames, allowedTools, null);
    }

    public SubtaskCapabilitySnapshot(Boolean reasoning, Boolean internetSearch, JsonNode knowledgeScopes,
                                     JsonNode knowledgeDocs, List<String> requestedSkillNames,
                                     List<ClientSkillPackage> clientSkillPackages,
                                     List<String> mcpServerNames, Set<String> allowedTools, String model) {
        this.reasoning = reasoning;
        this.internetSearch = internetSearch;
        this.knowledgeScopes = copy(knowledgeScopes);
        this.knowledgeDocs = copy(knowledgeDocs);
        this.requestedSkillNames = immutableStrings(requestedSkillNames);
        this.clientSkillPackages = clientSkillPackages == null ? List.of() : List.copyOf(clientSkillPackages);
        this.mcpServerNames = immutableStrings(mcpServerNames);
        this.allowedTools = allowedTools == null ? null : Set.copyOf(allowedTools);
        this.model = model == null || model.isBlank() ? null : model;
    }

    public static SubtaskCapabilitySnapshot empty() {
        return new SubtaskCapabilitySnapshot(null, null, null, null, List.of(), List.of(), Set.of());
    }

    public static SubtaskCapabilitySnapshot fromChatRequest(ChatRequest request) {
        return new SubtaskCapabilitySnapshot(request.getReasoning(), request.getInternetSearch(),
                request.getKnowledgeScopes(), request.getKnowledgeDocs(), request.getRequestedSkillNames(),
                request.getClientSkillPackages(), serverNames(request.getClientMcpServers()),
                allowedTools(request.getAvailableTools()), request.getModel());
    }

    public static SubtaskCapabilitySnapshot fromFeatures(JsonNode features, JsonNode availableTools) {
        JsonNode safe = features != null && features.isObject() ? features : MAPPER.createObjectNode();
        return new SubtaskCapabilitySnapshot(
                nullableBoolean(safe.get("reasoning")),
                nullableBoolean(safe.get("internet_search")),
                safe.get("knowledge_scopes"),
                safe.get("knowledge_docs"),
                strings(safe.get("requested_skill_names")),
                List.of(),
                strings(safe.get("client_mcp_server_names")),
                allowedTools(availableTools),
                text(safe.get("model")));
    }

    public ObjectNode toFeatures() {
        ObjectNode result = MAPPER.createObjectNode();
        if (reasoning != null) result.set("reasoning", MAPPER.getNodeFactory().booleanNode(reasoning));
        if (internetSearch != null) result.set("internet_search", MAPPER.getNodeFactory().booleanNode(internetSearch));
        if (knowledgeScopes != null) result.set("knowledge_scopes", knowledgeScopes.deepCopy());
        if (knowledgeDocs != null) result.set("knowledge_docs", knowledgeDocs.deepCopy());
        if (model != null) result.put("model", model);
        result.set("requested_skill_names", stringsNode(requestedSkillNames));
        result.set("client_mcp_server_names", stringsNode(mcpServerNames));
        return result;
    }

    public ArrayNode availableTools() {
        ArrayNode result = MAPPER.createArrayNode();
        if (allowedTools != null) allowedTools.stream().sorted().forEach(result::add);
        return result;
    }

    public Boolean getReasoning() { return reasoning; }
    public Boolean getInternetSearch() { return internetSearch; }
    public JsonNode getKnowledgeScopes() { return copy(knowledgeScopes); }
    public JsonNode getKnowledgeDocs() { return copy(knowledgeDocs); }
    public List<String> getRequestedSkillNames() { return requestedSkillNames; }
    public List<ClientSkillPackage> getClientSkillPackages() { return clientSkillPackages; }
    public List<String> getMcpServerNames() { return mcpServerNames; }
    public Set<String> getAllowedTools() { return allowedTools; }
    public String getModel() { return model; }

    public void apply(ChatRequest request) {
        request.setReasoning(reasoning);
        request.setInternetSearch(internetSearch);
        request.setKnowledgeScopes(copy(knowledgeScopes));
        request.setKnowledgeDocs(copy(knowledgeDocs));
        request.setRequestedSkillNames(requestedSkillNames);
        request.setClientSkillPackages(clientSkillPackages);
        request.setModel(model);
        List<JsonNode> tools = new ArrayList<>();
        availableTools().forEach(tools::add);
        request.setAvailableTools(tools);
    }

    private static Set<String> allowedTools(List<JsonNode> values) {
        if (values == null) return null;
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode item : values) {
            String name = item != null && item.isTextual() ? item.asText() : text(item, "name");
            if (name != null && !name.isBlank()) result.add(name);
        }
        return Set.copyOf(result);
    }

    private static Set<String> allowedTools(JsonNode value) {
        if (value == null || value.isNull()) return null;
        Set<String> result = new LinkedHashSet<>();
        if (value.isArray()) {
            for (JsonNode item : value) {
                String name = item.isTextual() ? item.asText() : text(item, "name");
                if (name != null && !name.isBlank()) result.add(name);
            }
        }
        return Set.copyOf(result);
    }

    private static List<String> serverNames(JsonNode servers) {
        if (servers == null || !servers.isObject()) return List.of();
        List<String> result = new ArrayList<>();
        servers.fieldNames().forEachRemaining(result::add);
        return List.copyOf(result);
    }

    private static List<String> strings(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) result.add(item.asText());
        });
        return List.copyOf(result);
    }

    private static List<String> immutableStrings(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).distinct().toList();
    }

    private static ArrayNode stringsNode(List<String> values) {
        ArrayNode result = MAPPER.createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private static Boolean nullableBoolean(JsonNode value) {
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    private static String text(JsonNode value, String field) {
        JsonNode item = value == null ? null : value.get(field);
        return item != null && item.isTextual() ? item.asText() : null;
    }

    private static String text(JsonNode value) {
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }
}
