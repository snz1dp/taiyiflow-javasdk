package io.taiyiflow.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class TaiyiAgentDefaultFlowTest {
    @Test
    void explicitDefaultFlowUsesServerConfiguration() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/config", exchange -> {
            byte[] response = "{\"default_flow_id\":\"server-flow\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            TaiyiAgent agent = TaiyiAgent.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey("key").requestTimeout(Duration.ofSeconds(2)).useServerDefaultFlow().build();
            assertEquals("server-flow", agent.getFlowId());
            agent.shutdown();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void omittedFlowIdUsesServerConfiguration() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/config", exchange -> {
            byte[] response = "{\"default_flow_id\":\"server-flow\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            TaiyiAgent agent = TaiyiAgent.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey("key").requestTimeout(Duration.ofSeconds(2)).build();
            assertEquals("server-flow", agent.getFlowId());
            agent.shutdown();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void defaultFlowAndExplicitFlowAreMutuallyExclusive() {
        assertThrows(IllegalArgumentException.class, () -> TaiyiAgent.builder().baseUrl("http://localhost")
                .apiKey("key").flowId("flow").useServerDefaultFlow().build());
    }
}
