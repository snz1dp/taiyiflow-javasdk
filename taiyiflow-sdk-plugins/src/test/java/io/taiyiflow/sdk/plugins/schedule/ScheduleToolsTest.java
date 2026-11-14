package io.taiyiflow.sdk.plugins.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import io.taiyiflow.sdk.transport.rest.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleToolsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private HttpServer server;
    private PluginManager plugins;
    private ToolExecutionContext context;
    private final List<String> requests = new ArrayList<>();
    private final List<JsonNode> bodies = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String request = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (requests) {
                requests.add(request);
                bodies.add(body.isBlank() ? MAPPER.nullNode() : MAPPER.readTree(body));
            }
            String path = exchange.getRequestURI().getPath();
            if ("DELETE".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                JsonNode response = path.endsWith("/runs") ? MAPPER.createArrayNode().addObject().put("id", "run-1")
                        : MAPPER.createObjectNode().put("id", "schedule-1");
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        plugins = new PluginManager();
        ScheduleTools.register(plugins, new RestClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "test", Duration.ofSeconds(2)));
        context = ToolExecutionContext.builder("trusted-flow", "session-1").build();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void delegatesAllScheduleOperationsToPlatformWithTrustedFlow() {
        ToolResult listed = execute("schedule_list", MAPPER.createObjectNode());
        assertTrue(listed.isSuccess(), listed::getError);
        assertTrue(requests.get(0).contains("/api/v1/agents/client/schedules?"));
        assertTrue(requests.get(0).contains("flow_id=trusted-flow"));

        ToolResult created = execute("schedule_create", MAPPER.createObjectNode()
                .put("name", "hourly").put("schedule_type", "interval").put("interval_seconds", 60)
                .put("prompt", "continue").put("flow_id", "forged-flow"));
        assertTrue(created.isSuccess(), created::getError);
        assertEquals("trusted-flow", bodies.get(1).path("flow_id").asText());

        ToolResult updated = execute("schedule_update", MAPPER.createObjectNode()
                .put("schedule_id", "schedule 1").put("schedule_type", "interval").put("interval_seconds", 120));
        assertTrue(updated.isSuccess(), updated::getError);
        assertTrue(requests.get(2).contains("/schedules/schedule%201"));
        assertFalse(bodies.get(2).has("schedule_id"));

        assertTrue(execute("schedule_toggle", MAPPER.createObjectNode()
                .put("schedule_id", "schedule-1").put("disabled", true)).isSuccess());
        assertTrue(requests.get(3).endsWith("/schedules/schedule-1/disable"));
        assertTrue(execute("schedule_runs", MAPPER.createObjectNode()
                .put("schedule_id", "schedule-1").put("limit", 5)).isSuccess());
        assertTrue(requests.get(4).contains("/schedules/schedule-1/runs?"));
        assertTrue(execute("schedule_delete", MAPPER.createObjectNode()
                .put("schedule_id", "schedule-1")).isSuccess());
        assertEquals(6, requests.size());
    }

    @Test
    void invalidScheduleDoesNotProduceAnyPlatformSideEffect() {
        ToolResult result = execute("schedule_create", MAPPER.createObjectNode()
                .put("name", "bad").put("schedule_type", "cron").put("cron_expr", "* * *")
                .put("prompt", "continue"));
        assertFalse(result.isSuccess());
        assertTrue(requests.isEmpty());
    }

    private ToolResult execute(String name, JsonNode arguments) {
        return plugins.execute(context, new ToolCall(name, name, arguments));
    }
}
