package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AhRestClientsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void modelsClientHitsFlowModelsEndpoint() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agents/flows/flow-1/models", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            respond(exchange, 200, "{\"default_model\":{\"model_key\":\"qwen-max\"},"
                    + "\"available_models\":[{\"model_key\":\"qwen-max\"}],\"future_field\":true}");
        });
        server.start();
        try {
            RestClient rest = new RestClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "key", Duration.ofSeconds(2));
            ModelsClient client = new ModelsClient(rest);
            JsonNode response = client.list("flow-1", "device");
            assertEquals("client_type=device", query.get());
            assertEquals("qwen-max", response.required("default_model").required("model_key").asText());
            assertTrue(response.required("future_field").asBoolean());
            client.list("flow-1");
            assertNull(query.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sessionGroupClientCoversCrudAndMoveContracts() throws Exception {
        AtomicReference<String> listQuery = new AtomicReference<>();
        AtomicReference<String> createBody = new AtomicReference<>();
        AtomicReference<String> updateBody = new AtomicReference<>();
        AtomicReference<String> moveBody = new AtomicReference<>();
        AtomicReference<String> deleted = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agents/session-groups", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.equals("/api/v1/agents/session-groups") && method.equals("GET")) {
                listQuery.set(exchange.getRequestURI().getQuery());
                respond(exchange, 200, "{\"offset\":0,\"total\":1,\"data\":[{\"id\":\"g-1\",\"name\":\"grp\"}]}");
            } else if (path.equals("/api/v1/agents/session-groups") && method.equals("POST")) {
                createBody.set(body);
                respond(exchange, 201, "{\"id\":\"g-1\",\"name\":\"grp\"}");
            } else if (path.equals("/api/v1/agents/session-groups/g-1") && method.equals("GET")) {
                respond(exchange, 200, "{\"id\":\"g-1\",\"name\":\"grp\",\"session_count\":2}");
            } else if (path.equals("/api/v1/agents/session-groups/g-1/update") && method.equals("POST")) {
                updateBody.set(body);
                respond(exchange, 200, "{\"id\":\"g-1\",\"name\":\"renamed\"}");
            } else if (path.equals("/api/v1/agents/session-groups/g-1") && method.equals("DELETE")) {
                deleted.set(path);
                respond(exchange, 200, "{}");
            } else if (path.equals("/api/v1/agents/session-groups/sessions") && method.equals("POST")) {
                moveBody.set(body);
                respond(exchange, 200, "{\"moved\":2}");
            } else {
                respond(exchange, 404, "{}");
            }
        });
        server.start();
        try {
            RestClient rest = new RestClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "key", Duration.ofSeconds(2));
            SessionGroupClient client = new SessionGroupClient(rest);

            JsonNode page = client.list("flow-1", "client-1", "kw", "name asc", 0, 50);
            assertEquals(1, page.required("total").asInt());
            assertTrue(listQuery.get().contains("flow_id=flow-1"));
            assertTrue(listQuery.get().contains("client_id=client-1"));
            assertTrue(listQuery.get().contains("order_by=name+asc"));

            client.create("flow-1", "grp");
            assertTrue(createBody.get().contains("\"flow_id\":\"flow-1\""));
            assertTrue(createBody.get().contains("\"name\":\"grp\""));

            assertEquals(2, client.get("g-1").required("session_count").asInt());

            client.update("g-1", MAPPER.createObjectNode().put("name", "renamed"));
            assertTrue(updateBody.get().contains("\"name\":\"renamed\""));

            client.moveSessions(List.of("s-1", "s-2"), "g-1");
            assertTrue(moveBody.get().contains("\"session_ids\":[\"s-1\",\"s-2\"]"));
            assertTrue(moveBody.get().contains("\"group_id\":\"g-1\""));

            client.moveSessions(List.of("s-1"), null);
            assertTrue(moveBody.get().contains("\"group_id\":null"));

            client.delete("g-1");
            assertEquals("/api/v1/agents/session-groups/g-1", deleted.get());
        } finally {
            server.stop(0);
        }
    }
}