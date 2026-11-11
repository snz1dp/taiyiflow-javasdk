package io.taiyiflow.sdk.transport.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolClientTest {

    @Test
    void initializesListsRegistersAndCallsToolsOverStreamableHttp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        List<String> sessionHeaders = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            requests.add(request);
            sessionHeaders.add(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
            assertEquals("application/json, text/event-stream", exchange.getRequestHeaders().getFirst("Accept"));
            String method = request.required("method").asText();
            switch (method) {
                case "initialize" -> {
                    assertEquals(McpToolClient.PROTOCOL_VERSION, request.at("/params/protocolVersion").asText());
                    exchange.getResponseHeaders().set("Mcp-Session-Id", "mcp-session-1");
                    respond(exchange, 200, "application/json",
                            "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":{\"protocolVersion\":\""
                                    + McpToolClient.PROTOCOL_VERSION + "\"}}");
                }
                case "notifications/initialized" -> respond(exchange, 202, "application/json", "");
                case "tools/list" -> respond(exchange, 200, "application/json", """
                        {"jsonrpc":"2.0","id":%s,"result":{"tools":[
                          {"name":"lookup","description":"Look up a value","inputSchema":{"type":"object"}}
                        ]}}
                        """.formatted(request.get("id")));
                case "tools/call" -> {
                    assertEquals("lookup", request.at("/params/name").asText());
                    assertEquals("key-1", request.at("/params/arguments/key").asText());
                    respond(exchange, 200, "text/event-stream", "event: message\ndata: "
                            + "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id")
                            + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"found\"}]}}\n\n");
                }
                default -> throw new AssertionError("Unexpected MCP method: " + method);
            }
        });
        server.start();

        McpToolClient client = new McpToolClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", Duration.ofSeconds(5));
        PluginManager plugins = new PluginManager();
        try {
            List<String> registered = client.registerTools("local", plugins);
            assertEquals(List.of("c_local_lookup"), registered);
            assertEquals("mcp-session-1", client.getSessionId());
            assertTrue(plugins.hasHandler("c_local_lookup"));

            ToolCall call = new ToolCall("call-1", "c_local_lookup",
                    mapper.createObjectNode().put("key", "key-1"));
            ToolResult result = plugins.execute(call);
            assertTrue(result.isSuccess());
            assertEquals("found", result.getResult().at("/content/0/text").asText());

            client.listTools();
            assertEquals(5, requests.size());
            assertEquals("initialize", requests.get(0).required("method").asText());
            assertEquals("notifications/initialized", requests.get(1).required("method").asText());
            assertEquals("tools/list", requests.get(2).required("method").asText());
            assertEquals("tools/call", requests.get(3).required("method").asText());
            assertEquals("tools/list", requests.get(4).required("method").asText());
            assertNull(sessionHeaders.get(0));
            assertEquals(List.of("mcp-session-1", "mcp-session-1", "mcp-session-1", "mcp-session-1"),
                    sessionHeaders.subList(1, 5));
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, status == 202 ? -1 : bytes.length);
        if (status != 202) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
