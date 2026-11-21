package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Path/payload contracts for the ecosystem REST clients, matched against taiyiflow-dev routes. */
class EcosystemClientsTest {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private record Route(int status, String body) { }

    private record Captured(String method, String path, String query, String body, String authorization) { }

    @Test
    void intentionRunAndMessageContract() throws Exception {
        List<Captured> captured = new CopyOnWriteArrayList<>();
        HttpServer server = server(Map.of(
                "/api/v1/agents/flows/flow name/runs/run-1/intention",
                new Route(200, "{\"id\":\"i-1\",\"session_id\":\"s-1\",\"run_id\":\"run-1\","
                        + "\"intention\":\"greeting\",\"relation_questions\":[\"q1\"],\"raw_payload\":{}}")), captured);
        try {
            IntentionClient client = new IntentionClient(rest(server));
            JsonNode intention = client.forRun("flow name", "run-1");
            assertEquals("greeting", intention.required("intention").asText());
            assertEquals("q1", intention.required("relation_questions").get(0).asText());
            assertNull(client.forMessage("flow name", "msg-1"));
            assertEquals("/api/v1/agents/flows/flow name/runs/run-1/intention", captured.get(0).path());
            assertEquals("/api/v1/agents/flows/flow name/messages/msg-1/intention", captured.get(1).path());
            assertEquals("GET", captured.get(0).method());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void intentionForbiddenPropagates() throws Exception {
        HttpServer server = server(Map.of(
                "/api/v1/agents/flows/flow/runs/run-1/intention",
                new Route(403, "{\"detail\":\"无权访问此智能服务应用\"}")), new CopyOnWriteArrayList<>());
        try {
            IntentionClient client = new IntentionClient(rest(server));
            RestClient.RestException error = assertThrows(RestClient.RestException.class,
                    () -> client.forRun("flow", "run-1"));
            assertEquals(403, error.getStatusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void desktopAndPrincipalDirectoryContracts() throws Exception {
        List<Captured> captured = new CopyOnWriteArrayList<>();
        HttpServer server = server(Map.of(
                "/api/v1/agents/client/online-desktops",
                new Route(200, "{\"online\":true,\"connections\":[{\"client_id\":\"c-1\",\"device_id\":\"d-1\","
                        + "\"client_type\":\"desktop\",\"can_receive_task\":true},"
                        + "{\"client_id\":\"c-2\",\"can_receive_task\":false}]}"),
                "/api/v1/agents/client/principals",
                new Route(200, "{\"data\":[{\"id\":\"p-1\",\"kind\":\"agent\",\"display_name\":\"P\","
                        + "\"status\":\"active\"}],\"total\":1}")), captured);
        try {
            ClientDirectoryClient client = new ClientDirectoryClient(rest(server));
            JsonNode desktops = client.onlineDesktops();
            assertTrue(desktops.required("online").asBoolean());
            assertEquals(2, desktops.required("connections").size());
            assertFalse(desktops.required("connections").get(1).required("can_receive_task").asBoolean());
            JsonNode principals = client.principals();
            assertEquals(1, principals.required("total").asInt());
            assertEquals("p-1", principals.required("data").get(0).required("id").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void quotaAndCreditPreflightContracts() throws Exception {
        List<Captured> captured = new CopyOnWriteArrayList<>();
        HttpServer server = server(Map.of(
                "/api/v1/quotas/usage/current",
                new Route(200, "{\"quota_type\":{\"code\":\"standard\"},\"current_period\":"
                        + "{\"quota_limit\":100,\"used_quota\":40,\"remaining_quota\":60}}"),
                "/api/v1/quotas/check",
                new Route(200, "{\"passed\":true,\"remaining_quota\":60,\"overage\":false}"),
                "/api/v1/credits/balance",
                new Route(200, "{\"total_balance\":120,\"accounts\":[]}"),
                "/api/v1/credits/check",
                new Route(200, "{\"pass\":true,\"required_credits\":3,\"active_account\":null}")), captured);
        try {
            QuotaCreditClient client = new QuotaCreditClient(rest(server));
            assertEquals(60, client.currentUsage().required("current_period").required("remaining_quota").asInt());
            assertTrue(client.checkQuota(2).required("passed").asBoolean());
            assertEquals("{\"required_credits\":2}", captured.get(1).body());
            assertEquals("POST", captured.get(1).method());
            assertEquals(120, client.balance("app-1").required("total_balance").asInt());
            assertEquals("owner_appid=app-1", captured.get(2).query());
            assertEquals(0, client.balance(null).required("accounts").size());
            assertNull(captured.get(3).query());

            assertTrue(client.checkCredits("qwen-max").required("pass").asBoolean());
            assertEquals("{\"model_key\":\"qwen-max\"}", captured.get(4).body());
            client.checkCredits("qwen-max", "chat", 500, 800, "app-1");
            JsonNode explicit = MAPPER.readTree(captured.get(5).body());
            assertEquals("chat", explicit.required("model_feature").asText());
            assertEquals(500, explicit.required("estimated_input_tokens").asInt());
            assertEquals(800, explicit.required("estimated_output_tokens").asInt());
            assertEquals("app-1", explicit.required("owner_appid").asText());
            assertThrows(IllegalArgumentException.class, () -> client.checkQuota(0));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void probePathsAndAuthentication() throws Exception {
        List<Captured> captured = new CopyOnWriteArrayList<>();
        HttpServer server = server(Map.of(
                "/health", new Route(200, "{\"status\":\"ok\"}"),
                "/api/health_check", new Route(200, "{\"status\":\"ok\",\"storage\":\"ok\",\"chat\":\"ok\",\"db\":\"ok\"}"),
                "/api/v1/version", new Route(200, "{\"version\":\"1.2.3\",\"main_version\":\"1.2\"}")), captured);
        try {
            PlatformProbeClient client = new PlatformProbeClient(rest(server));
            assertEquals("ok", client.health().required("status").asText());
            assertEquals("ok", client.healthCheck().required("db").asText());
            assertEquals("1.2.3", client.version().required("version").asText());
            assertEquals("/health", captured.get(0).path());
            assertEquals("/api/health_check", captured.get(1).path());
            assertEquals("/api/v1/version", captured.get(2).path());
            assertEquals("Bearer key", captured.get(2).authorization());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void catalogQueryContracts() throws Exception {
        List<Captured> captured = new CopyOnWriteArrayList<>();
        HttpServer server = server(Map.of(
                "/api/v1/agents/agent-apps", new Route(200, "[{\"id\":\"app-1\"}]"),
                "/api/v1/agents/plugins", new Route(200, "[{\"id\":\"plugin-1\"}]")), captured);
        try {
            RestClient rest = rest(server);
            assertEquals("app-1", new AgentAppClient(rest).list(true, 0, 50).get(0).required("id").asText());
            assertTrue(captured.get(0).query().contains("installed_only=true"));
            assertTrue(captured.get(0).query().contains("limit=50"));
            assertEquals("plugin-1", new AgentPluginClient(rest)
                    .list(true, "app-1", "desktop", 0, 100).get(0).required("id").asText());
            assertTrue(captured.get(1).query().contains("agent_app_id=app-1"));
            assertTrue(captured.get(1).query().contains("terminal_type=desktop"));
            new AgentAppClient(rest).list();
            assertFalse(captured.get(2).query().contains("installed_only"));
        } finally {
            server.stop(0);
        }
    }

    private static RestClient rest(HttpServer server) {
        return new RestClient("http://127.0.0.1:" + server.getAddress().getPort(), "key", Duration.ofSeconds(2));
    }

    private static HttpServer server(Map<String, Route> routes, List<Captured> captured) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(), body,
                    exchange.getRequestHeaders().getFirst("Authorization")));
            Route route = routes.get(exchange.getRequestURI().getPath());
            if (route == null) {
                respond(exchange, 404, "{\"detail\":\"not found\"}");
            } else {
                respond(exchange, route.status(), route.body());
            }
        });
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}