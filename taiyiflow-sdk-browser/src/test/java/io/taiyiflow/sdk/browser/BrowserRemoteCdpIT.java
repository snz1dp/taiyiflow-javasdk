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
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "TAIYIFLOW_TEST_CHROMIUM_EXECUTABLE", matches = ".+")
class BrowserRemoteCdpIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temporary;

    @Test
    void remoteCdpOwnsOnlySdkContextsAndDoesNotCloseExternalChrome() throws Exception {
        Path executable = Path.of(System.getenv("TAIYIFLOW_TEST_CHROMIUM_EXECUTABLE"));
        Path profile = temporary.resolve("external-profile");
        Files.createDirectories(profile);
        Process chrome = new ProcessBuilder(executable.toString(), "--headless=new",
                "--remote-debugging-port=0", "--user-data-dir=" + profile, "about:blank")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "<title>Remote</title><body>remote-sdk-page</body>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        BrowserRuntime runtime = null;
        try {
            Path activePort = profile.resolve("DevToolsActivePort");
            Instant deadline = Instant.now().plusSeconds(15);
            while (!Files.isRegularFile(activePort) && Instant.now().isBefore(deadline)) {
                if (!chrome.isAlive()) throw new IllegalStateException("External Chrome exited before CDP startup");
                Thread.sleep(50);
            }
            List<String> lines = Files.readAllLines(activePort);
            String endpoint = "http://127.0.0.1:" + lines.get(0);
            PluginManager plugins = new PluginManager();
            runtime = BrowserToolProvider.register(plugins,
                    new RestClient("http://127.0.0.1:1", "not-used", Duration.ofMillis(100)),
                    BrowserSettings.builder().mode(BrowserSettings.Mode.REMOTE_CDP)
                            .remoteEndpoint(endpoint).allowedHosts(Set.of("localhost"))
                            .actionTimeout(Duration.ofSeconds(20)).build());
            ToolExecutionContext context = ToolExecutionContext.builder("flow-a", "session-a")
                    .workspace(temporary.resolve("workspace")).build();

            ToolResult initial = execute(plugins, context, "tabs_context", MAPPER.createObjectNode());
            assertTrue(initial.isSuccess(), initial::getError);
            assertEquals(0, initial.getResult().path("tabs").size());
            ToolResult created = execute(plugins, context, "tabs_create", MAPPER.createObjectNode()
                    .put("url", "http://localhost:" + server.getAddress().getPort() + "/"));
            assertTrue(created.isSuccess(), created::getError);
            ToolResult text = execute(plugins, context, "read_text", MAPPER.createObjectNode());
            assertTrue(text.getResult().path("text").asText().contains("remote-sdk-page"));

            runtime.close();
            runtime = null;
            assertTrue(chrome.isAlive(), "Closing SDK runtime must not close external Chrome");
        } finally {
            if (runtime != null) runtime.close();
            server.stop(0);
            chrome.destroy();
            if (!chrome.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) chrome.destroyForcibly();
        }
    }

    private static ToolResult execute(PluginManager plugins, ToolExecutionContext context,
                                      String action, com.fasterxml.jackson.databind.JsonNode arguments) {
        return plugins.execute(context, new ToolCall(action, "taiyiflow-in-chrome__" + action, arguments));
    }
}
