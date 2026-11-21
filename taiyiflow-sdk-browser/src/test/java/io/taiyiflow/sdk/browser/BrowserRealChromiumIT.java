package io.taiyiflow.sdk.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import io.taiyiflow.sdk.transport.rest.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "TAIYIFLOW_TEST_CHROMIUM_EXECUTABLE", matches = ".+")
class BrowserRealChromiumIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void realHeadlessChromiumSupportsOwnedTabsActionsAndBoundedScreenshot() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] html = ("<!doctype html><title>SDK Browser</title><body><button id='go' "
                    + "onclick=\"this.textContent='done';document.body.dataset.changed='yes'\">go</button>"
                    + "</body>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        });
        server.start();
        PluginManager plugins = new PluginManager();
        BrowserRuntime runtime = BrowserToolProvider.register(plugins,
                new RestClient("http://127.0.0.1:1", "not-used", Duration.ofMillis(100)),
                BrowserSettings.builder().mode(BrowserSettings.Mode.HEADLESS)
                        .executablePath(Path.of(System.getenv("TAIYIFLOW_TEST_CHROMIUM_EXECUTABLE")))
                        .allowedHosts(Set.of("localhost")).actionTimeout(Duration.ofSeconds(20)).build());
        ToolExecutionContext context = ToolExecutionContext.builder("flow-a", "session-a")
                .workspace(workspace).build();
        try {
            ToolResult created = execute(plugins, context, "tabs_create", MAPPER.createObjectNode()
                    .put("url", "http://localhost:" + server.getAddress().getPort() + "/"));
            assertTrue(created.isSuccess(), created::getError);

            ToolResult ref = execute(plugins, context, "get_element_ref",
                    MAPPER.createObjectNode().put("selector", "#go"));
            assertTrue(ref.isSuccess(), ref::getError);
            String refId = ref.getResult().path("element_ref_id").asText();
            assertTrue(execute(plugins, context, "click",
                    MAPPER.createObjectNode().put("element_ref_id", refId)).isSuccess());
            ToolResult text = execute(plugins, context, "read_text", MAPPER.createObjectNode());
            assertTrue(text.getResult().path("text").asText().contains("done"));
            assertFalse(execute(plugins, context, "click",
                    MAPPER.createObjectNode().put("element_ref_id", refId)).isSuccess());

            ToolResult screenshot = execute(plugins, context, "screenshot", MAPPER.createObjectNode());
            assertTrue(screenshot.isSuccess(), screenshot::getError);
            Path image = workspace.resolve(screenshot.getResult().path("path").asText());
            assertTrue(Files.isRegularFile(image));
            assertTrue(Files.size(image) > 0);
            assertFalse(execute(plugins, context, "execute_js",
                    MAPPER.createObjectNode().put("code", "1 + 1")).isSuccess());

            plugins.notifySessionClose("session-a");
            ToolResult tabs = execute(plugins, context, "tabs_context", MAPPER.createObjectNode());
            assertTrue(tabs.getResult().path("tabs").isEmpty());
        } finally {
            if (runtime != null) runtime.close();
            server.stop(0);
        }
    }

    @Test
    void electronParameterAliasesDriveRealPageActions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] html = ("<!doctype html><body><div id='editable' contenteditable='true'>seed</div>"
                    + "<select id='choice'><option value='1'>One</option><option value='2'>Two</option></select>"
                    + "<script>document.getElementById('editable').addEventListener('keydown',"
                    + "e=>{if(e.key==='Enter')document.body.dataset.entered='yes'})</script>"
                    + "</body>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        });
        server.start();
        PluginManager plugins = new PluginManager();
        BrowserRuntime runtime = BrowserToolProvider.register(plugins,
                new RestClient("http://127.0.0.1:1", "not-used", Duration.ofMillis(100)),
                BrowserSettings.builder().mode(BrowserSettings.Mode.HEADLESS)
                        .executablePath(Path.of(System.getenv("TAIYIFLOW_TEST_CHROMIUM_EXECUTABLE")))
                        .allowedHosts(Set.of("localhost")).actionTimeout(Duration.ofSeconds(20)).build());
        ToolExecutionContext context = ToolExecutionContext.builder("flow-a", "session-a")
                .workspace(workspace).build();
        try {
            assertTrue(execute(plugins, context, "tabs_create", MAPPER.createObjectNode()
                    .put("url", "http://localhost:" + server.getAddress().getPort() + "/")).isSuccess());

            assertTrue(execute(plugins, context, "type", MAPPER.createObjectNode()
                    .put("selector", "#editable").put("text", "ab")).isSuccess());
            assertTrue(execute(plugins, context, "type", MAPPER.createObjectNode()
                    .put("selector", "#editable").put("text", "cd")
                    .put("append", true).put("press_enter", true)).isSuccess());
            ToolResult appended = execute(plugins, context, "read_text",
                    MAPPER.createObjectNode().put("selector", "#editable"));
            assertTrue(appended.getResult().path("text").asText().startsWith("abcd"),
                    appended.getResult()::toString);

            ToolResult bounded = execute(plugins, context, "read_text", MAPPER.createObjectNode()
                    .put("selector", "#editable").put("max_chars", 4));
            assertTrue(bounded.getResult().path("truncated").asBoolean());
            assertTrue(bounded.getResult().path("text").asText().startsWith("abcd"));

            ToolResult selected = execute(plugins, context, "select_option", MAPPER.createObjectNode()
                    .put("selector", "#choice").put("text", "Two"));
            assertTrue(selected.isSuccess(), selected::getError);
            assertTrue(selected.getResult().toString().contains("2"), selected.getResult()::toString);

            ToolResult rejected = execute(plugins, context, "screenshot",
                    MAPPER.createObjectNode().put("path", workspace.resolve("custom.png").toString()));
            assertFalse(rejected.isSuccess());
            assertTrue(rejected.getError().contains("does not accept a custom path"));
        } finally {
            if (runtime != null) runtime.close();
            server.stop(0);
        }
    }

    private static ToolResult execute(PluginManager plugins, ToolExecutionContext context,
                                      String action, com.fasterxml.jackson.databind.JsonNode arguments) {
        return plugins.execute(context, new ToolCall(action, "taiyiflow-in-chrome__" + action, arguments));
    }
}
