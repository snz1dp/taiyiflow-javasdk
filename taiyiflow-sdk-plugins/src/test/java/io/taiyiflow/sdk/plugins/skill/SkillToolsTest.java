package io.taiyiflow.sdk.plugins.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.core.skill.SkillRegistry;
import io.taiyiflow.sdk.core.skill.SkillSource;
import io.taiyiflow.sdk.core.skill.SkillSupplier;
import io.taiyiflow.sdk.core.skill.SkillSupplyPolicy;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import io.taiyiflow.sdk.transport.rest.SkillClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SkillToolsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path temp;

    @Test
    void listsCreatesFetchesUploadsAndDeletesWorkspaceSkills() throws Exception {
        Path workspace = temp.resolve("workspace");
        Path user = temp.resolve("user");
        AtomicInteger uploads = new AtomicInteger();
        SkillClient client = new SkillClient(null) {
            @Override public JsonNode uploadArchive(String name, String displayName, String filename, byte[] content) {
                uploads.incrementAndGet();
                return MAPPER.createObjectNode().put("id", "platform-id");
            }
        };
        SkillManager manager = new SkillManager(workspace, user, client);
        SkillSupplier supplier = new SkillSupplier(SkillRegistry.builder()
                .directory(workspace, SkillSource.WORKSPACE).directory(user, SkillSource.USER).build(),
                SkillSupplyPolicy.localOnly());
        PluginManager plugins = new PluginManager();
        SkillTools.register(plugins, supplier, manager, List.of("agent"));

        ToolResult created = execute(plugins, "skill_create", MAPPER.createObjectNode()
                .put("slug", "demo").put("content", "---\nname: demo\n---\nUseful instructions"));
        assertTrue(created.isSuccess(), created.getError());
        assertTrue(Files.isRegularFile(workspace.resolve("demo/SKILL.md")));

        ToolResult listed = execute(plugins, "skill_list", MAPPER.createObjectNode());
        assertEquals("demo", listed.getResult().get("skills").get(0).get("slug").asText());
        ToolResult fetched = execute(plugins, "skill_fetch", MAPPER.createObjectNode().put("name", "demo"));
        assertTrue(fetched.getResult().get("content").asText().contains("Useful instructions"));
        ToolResult uploaded = execute(plugins, "skill_upload", MAPPER.createObjectNode().put("name", "demo"));
        assertTrue(uploaded.isSuccess(), uploaded.getError());
        assertEquals(1, uploads.get());

        ToolResult deleted = execute(plugins, "skill_delete", MAPPER.createObjectNode().put("name", "demo"));
        assertTrue(deleted.isSuccess(), deleted.getError());
        assertFalse(Files.exists(workspace.resolve("demo")));
        assertFalse(execute(plugins, "skill_fetch", MAPPER.createObjectNode().put("name", "demo")).isSuccess());
    }

    @Test
    void rejectsCreateWithoutValidatedSlug() {
        Path workspace = temp.resolve("workspace");
        SkillManager manager = new SkillManager(workspace, temp.resolve("user"), null);
        SkillSupplier supplier = new SkillSupplier(SkillRegistry.builder()
                .directory(workspace, SkillSource.WORKSPACE).build(), SkillSupplyPolicy.localOnly());
        PluginManager plugins = new PluginManager();
        SkillTools.register(plugins, supplier, manager, List.of());
        ToolResult result = execute(plugins, "skill_create", MAPPER.createObjectNode()
                .put("content", "---\nname: demo\n---\nbody"));
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("slug"));
    }

    private ToolResult execute(PluginManager plugins, String name, ObjectNode arguments) {
        ToolExecutionContext context = ToolExecutionContext.builder("flow", "session")
                .workspace(temp).requestMode("agent").build();
        return plugins.execute(context, new ToolCall("call-" + name, name, arguments));
    }
}
