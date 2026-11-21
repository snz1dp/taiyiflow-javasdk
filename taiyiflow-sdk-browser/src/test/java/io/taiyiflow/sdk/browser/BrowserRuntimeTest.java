package io.taiyiflow.sdk.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import io.taiyiflow.sdk.transport.rest.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserRuntimeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> ACTIONS = List.of(
            "tabs_context", "tabs_create", "navigate", "set_view_size", "wait_for", "click", "type",
            "hover", "scroll", "select_option", "press_key", "get_element_info", "get_element_ref",
            "screenshot", "read_text", "read_console", "execute_js");

    @Test
    void disabledModeDoesNotCreateRuntimeOrRegisterTools() {
        PluginManager plugins = new PluginManager();
        BrowserRuntime runtime = BrowserToolProvider.register(plugins, restClient(), BrowserSettings.disabled());
        assertNull(runtime);
        assertTrue(plugins.getDefinitions().isEmpty());
    }

    @Test
    void registersAllToolsAndSetBrowserTypeIsExplicitlyUnsupported() {
        PluginManager plugins = new PluginManager();
        BrowserRuntime runtime = BrowserToolProvider.register(plugins, restClient(),
                BrowserSettings.builder().mode(BrowserSettings.Mode.HEADLESS).build());
        try {
            assertEquals(ACTIONS.size() + 1, plugins.getDefinitions().size());
            ACTIONS.forEach(action -> assertTrue(plugins.hasHandler("taiyiflow-in-chrome__" + action)));
            assertTrue(plugins.hasHandler("taiyiflow-in-chrome__set_browser_type"));

            ToolResult unsupported = plugins.execute(context("flow-a", "session-a"),
                    new ToolCall("1", "taiyiflow-in-chrome__set_browser_type",
                            MAPPER.createObjectNode().put("os", "android").put("browser", "chrome")));
            assertFalse(unsupported.isSuccess());
            assertTrue(unsupported.getError().contains("not supported (Chromium-only)"), unsupported::getError);
            assertTrue(unsupported.getError().contains("Do not retry"), unsupported::getError);

            ToolResult result = plugins.execute(context("flow-a", "session-a"),
                    new ToolCall("1", "taiyiflow-in-chrome__tabs_context", MAPPER.createObjectNode()));

            assertTrue(result.isSuccess(), result::getError);
            assertEquals(0, result.getResult().path("tabs").size());
            assertEquals("HEADLESS", result.getResult().path("mode").asText());
            assertEquals("chromium", result.getResult().path("browser_type").asText());
        } finally {
            if (runtime != null) runtime.close();
        }
    }

    @Test
    void browserToolsNeverTrustModelSuppliedFlowOrSessionIdentity() {
        PluginManager plugins = new PluginManager();
        BrowserRuntime runtime = BrowserToolProvider.register(plugins, restClient(),
                BrowserSettings.builder().mode(BrowserSettings.Mode.HEADLESS).build());
        try {
            ToolResult result = plugins.execute(ToolExecutionContext.standalone("model-session"),
                    new ToolCall("1", "taiyiflow-in-chrome__tabs_create", MAPPER.createObjectNode()
                            .put("flow_id", "forged-flow").put("session_id", "forged-session")));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("trusted flowId and sessionId"));
        } finally {
            if (runtime != null) runtime.close();
        }
    }

    @Test
    void executeJsHasAnIndependentSwitchEvenWhenOtherBrowserToolsAreEnabled() {
        assertFalse(BrowserSettings.builder().mode(BrowserSettings.Mode.HEADLESS).build().isExecuteJsEnabled());
        assertTrue(BrowserSettings.builder().mode(BrowserSettings.Mode.HEADLESS)
                .executeJsEnabled(true).build().isExecuteJsEnabled());
    }

    @Test
    void remoteSettingsRedactQueryAndRejectUserInfoAndHeaderInjection() {
        BrowserSettings settings = BrowserSettings.builder().mode(BrowserSettings.Mode.REMOTE_CDP)
                .remoteEndpointSecret("CDP_URL", ignored -> "wss://browser.example/cdp?token=top-secret")
                .remoteHeaderSecret("Authorization", "CDP_AUTH", ignored -> "Bearer private")
                .build();
        assertEquals("wss://browser.example", settings.redactedRemoteHost());
        assertThrows(IllegalArgumentException.class, () -> BrowserSettings.builder()
                .mode(BrowserSettings.Mode.REMOTE_CDP).remoteEndpoint("wss://user:pass@example.test/cdp").build());
        assertThrows(IllegalArgumentException.class, () -> BrowserSettings.builder()
                .remoteHeader("X-Test", "safe\r\nX-Injected: true"));
    }

    @Test
    void sessionCloseNotificationIsRegisteredForOwnedPageCleanup() {
        PluginManager plugins = new PluginManager();
        BrowserRuntime runtime = BrowserToolProvider.register(plugins, restClient(),
                BrowserSettings.builder().mode(BrowserSettings.Mode.HEADLESS).build());
        try {
            plugins.notifySessionClose("session-without-pages");
            ToolResult result = plugins.execute(context("flow-a", "session-after-close"),
                    new ToolCall("1", "taiyiflow-in-chrome__tabs_context", MAPPER.createObjectNode()));
            assertTrue(result.isSuccess(), result::getError);
        } finally {
            if (runtime != null) runtime.close();
        }
    }

    @Test
    void screenshotUnderstandingIsNotBoundedByBrowserActionTimeout(@TempDir Path workspace) throws Exception {
        String relative = ".snz1dp/browser/test.png";
        Path screenshot = workspace.resolve(relative);
        Files.createDirectories(screenshot.getParent());
        Files.write(screenshot, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47});
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> understandingPrompts = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/api/v1/config", exchange -> respond(exchange, 200,
                "{\"image_flow_id\":\"image-flow\",\"max_file_size_upload\":1}"));
        server.createContext("/api/v1/agents/upload/business-flow", exchange -> respond(exchange, 200,
                "{\"flow_id\":\"business-flow\",\"file_path\":\"/uploads/test.png\"}"));
        server.createContext("/api/v1/run/image-flow", exchange -> {
            understandingPrompts.add(MAPPER.readTree(exchange.getRequestBody()).path("input_value").asText());
            respond(exchange, 202, "{\"task_id\":\"task-image-1\",\"status\":\"pending\"}");
        });
        server.createContext("/api/v1/run/tasks/task-image-1", exchange -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            respond(exchange, 200, "{\"task_id\":\"task-image-1\",\"status\":\"success\",\"result\":{"
                    + "\"session_id\":\"session-a\",\"media_type\":\"text/plain\",\"outputs\":[\"described\"]}}");
        });
        server.start();
        BrowserRuntime runtime = new BrowserRuntime(BrowserSettings.builder()
                .mode(BrowserSettings.Mode.HEADLESS)
                .actionTimeout(Duration.ofMillis(1))
                .build(), new RestClient("http://127.0.0.1:" + server.getAddress().getPort(), "key",
                Duration.ofSeconds(2)));
        try {
            ToolExecutionContext context = ToolExecutionContext.builder("business-flow", "session-a")
                    .workspace(workspace).build();
            ObjectNode result = MAPPER.createObjectNode().put("path", relative)
                    .put("viewport_width", 1280).put("viewport_height", 720);
            runtime.addImageUnderstanding(context, result, MAPPER.createObjectNode().put("input", "describe"));

            assertEquals("described", result.path("image_understanding").asText());
            assertEquals(relative, result.path("path").asText());
            assertEquals(1, understandingPrompts.size());
            assertTrue(understandingPrompts.get(0).startsWith("describe"));

            ObjectNode aliased = MAPPER.createObjectNode().put("path", relative)
                    .put("viewport_width", 1280).put("viewport_height", 720);
            runtime.addImageUnderstanding(context, aliased,
                    MAPPER.createObjectNode().put("input_value", "alias-prompt"));
            assertEquals("described", aliased.path("image_understanding").asText());
            assertEquals(2, understandingPrompts.size());
            assertTrue(understandingPrompts.get(1).startsWith("alias-prompt"));

            ObjectNode failed = MAPPER.createObjectNode().put("path", "missing.png")
                    .put("viewport_width", 1280).put("viewport_height", 720);
            runtime.addImageUnderstanding(context, failed, MAPPER.createObjectNode());
            assertEquals("missing.png", failed.path("path").asText());
            assertFalse(failed.path("image_understanding_error").asText().isBlank());
        } finally {
            runtime.close();
            server.stop(0);
        }
    }

    @Test
    void electronParameterAliasesAreAdvertisedAlongsideCanonicalNames() {
        PluginManager plugins = new PluginManager();
        BrowserRuntime runtime = BrowserToolProvider.register(plugins, restClient(),
                BrowserSettings.builder().mode(BrowserSettings.Mode.HEADLESS).build());
        try {
            assertTrue(schemaProperties(plugins, "type").has("append"));
            assertTrue(schemaProperties(plugins, "type").has("press_enter"));
            assertTrue(schemaProperties(plugins, "type").has("clear"));
            assertTrue(schemaProperties(plugins, "type").has("submit"));
            assertTrue(schemaProperties(plugins, "select_option").has("text"));
            assertTrue(schemaProperties(plugins, "select_option").has("label"));
            assertTrue(schemaProperties(plugins, "read_text").has("max_chars"));
            assertTrue(schemaProperties(plugins, "read_text").has("max_characters"));
            assertTrue(schemaProperties(plugins, "screenshot").has("input_value"));
            assertTrue(schemaProperties(plugins, "screenshot").has("input"));
            assertFalse(schemaProperties(plugins, "screenshot").has("path"));
        } finally {
            if (runtime != null) runtime.close();
        }
    }

    @Test
    void screenshotRejectsCustomPathExplicitlyInsteadOfSilentlyIgnoring() {
        PluginManager plugins = new PluginManager();
        BrowserRuntime runtime = BrowserToolProvider.register(plugins, restClient(),
                BrowserSettings.builder().mode(BrowserSettings.Mode.HEADLESS).build());
        try {
            ToolResult result = plugins.execute(context("flow-a", "session-a"),
                    new ToolCall("1", "taiyiflow-in-chrome__screenshot",
                            MAPPER.createObjectNode().put("path", "/tmp/escape.png")));

            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("does not accept a custom path"), result::getError);
            assertTrue(result.getError().contains(".snz1dp/browser/"), result::getError);
        } finally {
            if (runtime != null) runtime.close();
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode schemaProperties(PluginManager plugins, String action) {
        return plugins.getDefinitions().stream()
                .filter(definition -> definition.getName().equals("taiyiflow-in-chrome__" + action))
                .findFirst().orElseThrow()
                .getInputSchema().path("properties");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static ToolExecutionContext context(String flow, String session) {
        return ToolExecutionContext.builder(flow, session).build();
    }

    private static RestClient restClient() {
        return new RestClient("http://127.0.0.1:1", "not-used", Duration.ofMillis(100));
    }
}
