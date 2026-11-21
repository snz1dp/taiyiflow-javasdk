package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallRecordClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void listsRecordsWithBackendQueryContract() throws Exception {
        List<String> queries = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agents/flows/flow-1/sessions/session-1/messages/message-1/tool-calls", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            ObjectNode record = MAPPER.createObjectNode()
                    .put("id", "record-1")
                    .put("call_id", "call-1")
                    .put("call_index", 0)
                    .put("tool_type", "client_tool")
                    .put("tool_id", "shell")
                    .put("tool_name", "shell")
                    .put("run_id", "run-1")
                    .put("via_client", true)
                    .put("status", "success")
                    .put("result", "ok")
                    .put("finished_at", "2026-11-21T00:00:00Z");
            record.set("arguments", MAPPER.createObjectNode().put("command", "ls"));
            ObjectNode page = MAPPER.createObjectNode().put("total", 1).put("offset", 0);
            page.set("data", MAPPER.createArrayNode().add(record));
            byte[] body = MAPPER.writeValueAsBytes(page);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ToolCallRecordClient client = new ToolCallRecordClient(new RestClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "key", Duration.ofSeconds(5)));
            ToolCallRecordClient.Page page = client.list("flow-1", "session-1", "message-1",
                    new ToolCallRecordClient.Query("run-1", "client_tool", true, "created_at asc", 0, 50));

            assertEquals(1, page.total());
            ToolCallRecordClient.Record record = page.data().get(0);
            assertEquals("call-1", record.callId());
            assertEquals("client_tool", record.toolType());
            assertTrue(record.viaClient());
            assertTrue(record.resultReceived());
            assertEquals("ls", record.arguments().path("command").asText());
            assertEquals("success", record.raw().path("status").asText());

            Map<String, String> query = parseQuery(queries.get(0));
            assertEquals("run-1", query.get("run_id"));
            assertEquals("client_tool", query.get("tool_type"));
            assertEquals("true", query.get("via_client"));
            assertEquals("created_at asc", query.get("order_by"));
            assertEquals("0", query.get("offset"));
            assertEquals("50", query.get("limit"));

            client.list("flow-1", "session-1", "message-1", null);
            Map<String, String> defaults = parseQuery(queries.get(1));
            assertNull(defaults.get("run_id"));
            assertNull(defaults.get("via_client"));
            assertEquals("100", defaults.get("limit"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void byCallIdPaginatesUntilTotalReached() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agents/flows/flow-1/sessions/session-1/messages/message-1/tool-calls", exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            int offset = Integer.parseInt(query.getOrDefault("offset", "0"));
            ArrayNode data = MAPPER.createArrayNode();
            // Server caps the page at two records regardless of the requested limit.
            for (int index = offset; index < Math.min(offset + 2, 3); index++) {
                data.add(MAPPER.createObjectNode()
                        .put("id", "record-" + index)
                        .put("call_id", "call-" + index)
                        .put("tool_type", "client_tool")
                        .put("tool_id", "tool")
                        .put("status", "success"));
            }
            ObjectNode page = MAPPER.createObjectNode().put("total", 3).put("offset", offset);
            page.set("data", data);
            byte[] body = MAPPER.writeValueAsBytes(page);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ToolCallRecordClient client = new ToolCallRecordClient(new RestClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "key", Duration.ofSeconds(5)));
            Map<String, ToolCallRecordClient.Record> byCallId =
                    client.byCallId("flow-1", "session-1", "message-1", "run-1", true);
            assertEquals(List.of("call-0", "call-1", "call-2"), List.copyOf(byCallId.keySet()));
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return values;
    }
}