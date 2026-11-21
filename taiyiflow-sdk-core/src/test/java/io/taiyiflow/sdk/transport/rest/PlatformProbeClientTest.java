package io.taiyiflow.sdk.transport.rest;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformProbeClientTest {

    @Test
    void openProbesOmitAuthWhileVersionKeepsIt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> authByPath = new ConcurrentHashMap<>();
        Map<String, String> credentialByPath = new ConcurrentHashMap<>();
        HttpHandler handler = exchange -> {
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String credential = exchange.getRequestHeaders().getFirst("X-Credential-Principal");
            authByPath.put(path, auth == null ? "" : auth);
            credentialByPath.put(path, credential == null ? "" : credential);
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        };
        server.createContext("/health", handler);
        server.createContext("/api/health_check", handler);
        server.createContext("/api/v1/version", handler);
        server.start();
        try {
            RestClient rest = new RestClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "key", Duration.ofSeconds(2), Map.of("X-Credential-Principal", "p1"));
            PlatformProbeClient probes = new PlatformProbeClient(rest);
            probes.health();
            probes.healthCheck();
            probes.version();

            assertEquals("", authByPath.get("/health"));
            assertEquals("", credentialByPath.get("/health"));
            assertEquals("", authByPath.get("/api/health_check"));
            assertEquals("", credentialByPath.get("/api/health_check"));
            assertEquals("Bearer key", authByPath.get("/api/v1/version"));
            assertEquals("p1", credentialByPath.get("/api/v1/version"));
        } finally {
            server.stop(0);
        }
    }
}