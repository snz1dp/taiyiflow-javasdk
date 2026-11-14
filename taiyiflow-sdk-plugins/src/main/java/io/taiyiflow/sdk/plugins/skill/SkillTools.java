package io.taiyiflow.sdk.plugins.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.skill.SkillDefinition;
import io.taiyiflow.sdk.core.skill.SkillDocumentParser;
import io.taiyiflow.sdk.core.skill.SkillQuery;
import io.taiyiflow.sdk.core.skill.SkillSelection;
import io.taiyiflow.sdk.core.skill.SkillSource;
import io.taiyiflow.sdk.core.skill.SkillSupplier;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ClientSkillFile;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.taiyiflow.sdk.plugins.ToolDefinitions.tool;

public final class SkillTools {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SkillSupplier supplier;
    private final SkillManager manager;
    private final List<String> defaultNames;

    private SkillTools(SkillSupplier supplier, SkillManager manager, List<String> defaultNames) {
        this.supplier = supplier;
        this.manager = manager;
        this.defaultNames = defaultNames == null ? List.of() : List.copyOf(defaultNames);
    }

    public static void register(PluginManager plugins, SkillSupplier supplier, SkillManager manager,
                                List<String> defaultNames) {
        SkillTools tools = new SkillTools(supplier, manager, defaultNames);
        plugins.register(tool("skill_list", "List skills visible to the current session", "{}"),
                (PluginManager.ContextualToolHandler) tools::list);
        plugins.register(tool("skill_fetch", "Fetch visible skill content or a declared attachment",
                "{\"name\":{\"type\":\"string\"},\"file\":{\"type\":\"string\"}}", "name"),
                (PluginManager.ContextualToolHandler) tools::fetch);
        plugins.register(tool("skill_create", "Create a local skill in an allowed scope",
                "{\"slug\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},"
                        + "\"scope\":{\"type\":\"string\",\"enum\":[\"workspace\",\"user\"]}}",
                "slug", "content"), (PluginManager.ContextualToolHandler) tools::create);
        plugins.register(tool("skill_upload", "Upload a validated local skill archive to the platform",
                "{\"name\":{\"type\":\"string\"}}", "name"),
                (PluginManager.ContextualToolHandler) tools::upload);
        plugins.register(tool("skill_delete", "Delete an allowed local or platform skill",
                "{\"name\":{\"type\":\"string\"}}", "name"),
                (PluginManager.ContextualToolHandler) tools::delete);
    }

    private ToolResult list(ToolExecutionContext context, ToolCall call) {
        try {
            ArrayNode skills = MAPPER.createArrayNode();
            for (SkillDefinition skill : supplier.visibleSkills(query(context), selection(context))) {
                ObjectNode item = skills.addObject();
                item.put("name", skill.name());
                item.put("slug", skill.slug());
                item.put("description", skill.description());
                item.put("summary", skill.summary());
                item.put("source", skill.source().wireName());
                item.put("version", skill.version());
                item.put("read_only", skill.source().readOnly());
                ArrayNode missing = item.putArray("missing_env");
                SkillDocumentParser.missingEnvironment(skill).forEach(missing::add);
            }
            ObjectNode result = MAPPER.createObjectNode();
            result.set("skills", skills);
            return ToolResult.success(call.getId(), result);
        } catch (Exception exception) {
            return ToolResult.error(call.getId(), safeMessage(exception));
        }
    }

    private ToolResult fetch(ToolExecutionContext context, ToolCall call) {
        try {
            String name = required(call.getArguments(), "name");
            SkillDefinition skill = supplier.fetch(query(context), selection(context), name);
            if (skill == null) return ToolResult.error(call.getId(), "Unknown or unavailable skill");
            String file = call.getArguments().path("file").asText("");
            ObjectNode result = MAPPER.createObjectNode();
            result.put("name", skill.name());
            result.put("slug", skill.slug());
            result.put("source", skill.source().wireName());
            if (file.isBlank()) {
                result.put("content", skill.body());
                result.put("is_summary", false);
            } else {
                result.put("file", file);
                result.put("content", readDeclaredFile(skill, file));
            }
            return ToolResult.success(call.getId(), result);
        } catch (Exception exception) {
            return ToolResult.error(call.getId(), safeMessage(exception));
        }
    }

    private ToolResult create(ToolExecutionContext context, ToolCall call) {
        try {
            String scopeName = call.getArguments().path("scope").asText("workspace");
            SkillSource scope = "user".equals(scopeName) ? SkillSource.USER : SkillSource.WORKSPACE;
            SkillDefinition created = manager.create(scope, required(call.getArguments(), "slug"),
                    required(call.getArguments(), "content"));
            ObjectNode result = MAPPER.createObjectNode();
            result.put("created", true);
            result.put("name", created.slug());
            result.put("scope", scope.wireName());
            return ToolResult.success(call.getId(), result);
        } catch (Exception exception) {
            return ToolResult.error(call.getId(), safeMessage(exception));
        }
    }

    private ToolResult upload(ToolExecutionContext context, ToolCall call) {
        try {
            SkillDefinition skill = local(context, call);
            JsonNode response = manager.upload(skill);
            ObjectNode result = MAPPER.createObjectNode();
            result.put("uploaded", true);
            result.put("name", skill.slug());
            if (response != null && response.path("id").isTextual()) result.put("id", response.path("id").asText());
            return ToolResult.success(call.getId(), result);
        } catch (Exception exception) {
            return ToolResult.error(call.getId(), safeMessage(exception));
        }
    }

    private ToolResult delete(ToolExecutionContext context, ToolCall call) {
        try {
            String name = required(call.getArguments(), "name");
            SkillDefinition skill = supplier.find(query(context), selection(context), name);
            if (skill == null) return ToolResult.error(call.getId(), "Unknown or unavailable skill");
            manager.delete(skill);
            ObjectNode result = MAPPER.createObjectNode();
            result.put("deleted", true);
            result.put("name", skill.slug());
            result.put("source", skill.source().wireName());
            return ToolResult.success(call.getId(), result);
        } catch (Exception exception) {
            return ToolResult.error(call.getId(), safeMessage(exception));
        }
    }

    private SkillDefinition local(ToolExecutionContext context, ToolCall call) {
        String name = required(call.getArguments(), "name");
        SkillDefinition skill = supplier.fetch(query(context), selection(context), name);
        if (skill == null || skill.source() == SkillSource.PLATFORM || skill.source().readOnly()
                || skill.skillDir() == null) throw new IllegalArgumentException("Only mutable local skills can be uploaded");
        return skill;
    }

    private SkillQuery query(ToolExecutionContext context) {
        return new SkillQuery(context.getFlowId(), context.getSessionId(), context.getRequestMode(),
                context.getFlowId() + "\0" + context.getSessionId(), context.getAllowedTools());
    }

    private SkillSelection selection(ToolExecutionContext context) {
        List<String> requested = context.getCapabilities() == null ? List.of()
                : context.getCapabilities().getRequestedSkillNames();
        return new SkillSelection(requested, defaultNames, true, null);
    }

    private static String readDeclaredFile(SkillDefinition skill, String requested) throws Exception {
        String relative = SkillArchives.safePath(requested);
        if (skill.skillDir() == null || skill.files().stream().map(ClientSkillFile::path).noneMatch(relative::equals)) {
            throw new IllegalArgumentException("File is not declared by the skill");
        }
        Path root = skill.skillDir().toRealPath();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || Files.isSymbolicLink(file) || !Files.isRegularFile(file)
                || Files.size(file) > 1_000_000 || !file.toRealPath().startsWith(root)) {
            throw new IllegalArgumentException("Declared skill file is unavailable");
        }
        return Files.readString(file);
    }

    private static String required(JsonNode arguments, String field) {
        String value = arguments == null ? "" : arguments.path(field).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing '" + field + "'");
        return value;
    }

    private static String safeMessage(Exception exception) {
        if (!(exception instanceof IllegalArgumentException)) return "Skill operation failed";
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Invalid skill operation" : message;
    }
}
