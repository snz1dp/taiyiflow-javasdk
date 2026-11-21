package io.taiyiflow.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaiyiAgentServerConfigTest {

    @Test
    void configFetchFailureIsRetriedAfterTheNegativeCacheWindow() throws Exception {
        long originalWindow = TaiyiAgent.serverConfigNegativeCacheMs;
        TaiyiAgent.serverConfigNegativeCacheMs = 1000;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger configCalls = new AtomicInteger();
        server.createContext("/api/v1/config", exchange -> {
            int attempt = configCalls.incrementAndGet();
            byte[] body = (attempt == 1 ? "{\"detail\":\"unavailable\"}"
                    : "{\"client_max_session_concurrency\":2}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(attempt == 1 ? 500 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            TaiyiAgent agent = TaiyiAgent.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey("key").flowId("flow-1").requestTimeout(Duration.ofSeconds(2)).build();
            try {
                // First fetch fails: concurrency stays unlimited and the failure is cached briefly.
                assertEquals(-1, agent.getMaxSessionConcurrency());
                assertEquals(1, configCalls.get());
                // Within the window the cached failure is reused without re-hitting the server.
                assertEquals(-1, agent.getMaxSessionConcurrency());
                assertEquals(1, configCalls.get());

                Thread.sleep(1100);

                // After the window a recovered server is picked up and cached permanently.
                assertEquals(2, agent.getMaxSessionConcurrency());
                assertEquals(2, configCalls.get());
                assertEquals(2, agent.getMaxSessionConcurrency());
                assertEquals(2, configCalls.get());
            } finally {
                agent.shutdown();
            }
        } finally {
            TaiyiAgent.serverConfigNegativeCacheMs = originalWindow;
            server.stop(0);
        }
    }
}