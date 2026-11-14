package io.taiyiflow.sdk.core.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.hooks.PermissionPolicy;
import io.taiyiflow.sdk.transport.envelope.FlowMetadata;
import io.taiyiflow.sdk.transport.envelope.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void handlerOnlyToolsAreNotAdvertisedAndFeaturesAreEnforced() throws Exception {
        PluginManager plugins = new PluginManager();
        ObjectNodeBuilder schema = new ObjectNodeBuilder();
        plugins.register("hidden", call -> null);
        plugins.register(new ToolDefinition("document_unstructured", "Parse documents", schema.object(),
                "document_understanding"), call -> null);
        plugins.register(new ToolDefinition("read_file", "Read files", schema.object()), call -> null);

        FlowMetadata allowed = new FlowMetadata(MAPPER.readTree("{\"features\":[\"document_understanding\"]}"));
        List<JsonNode> tools = ToolCatalog.automatic(plugins, allowed, "agent", null, PermissionPolicy.CONFIRM);
        assertEquals(2, tools.size());
        assertFalse(tools.stream().anyMatch(tool -> tool.path("name").asText().equals("hidden")));

        FlowMetadata denied = new FlowMetadata(MAPPER.readTree("{\"features\":[]}"));
        tools = ToolCatalog.automatic(plugins, denied, "agent", null, PermissionPolicy.CONFIRM);
        assertEquals(List.of("read_file"), tools.stream().map(tool -> tool.path("name").asText()).toList());
    }

    @Test
    void explicitListCannotInventLocalHandlersOrBypassReadonly() throws Exception {
        PluginManager plugins = new PluginManager();
        ObjectNodeBuilder schema = new ObjectNodeBuilder();
        plugins.register(new ToolDefinition("write_file", "Write files", schema.object()), call -> null);
        List<JsonNode> requested = List.of(MAPPER.readTree("{\"name\":\"write_file\"}"),
                MAPPER.readTree("{\"name\":\"remote_mcp_tool\"}"));
        List<JsonNode> filtered = ToolCatalog.explicit(plugins, requested, null, "agent", null,
                PermissionPolicy.READONLY);
        assertEquals(List.of("remote_mcp_tool"), filtered.stream().map(tool -> tool.path("name").asText()).toList());
    }

    private static final class ObjectNodeBuilder {
        private int calls;
        JsonNode object() {
            ObjectMapper mapper = new ObjectMapper();
            var schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties").putObject("value" + calls++).put("type", "string");
            return schema;
        }
    }
}
