package io.taiyiflow.sdk.transport.rest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestClientTest {

    @Test
    void requestTimeoutIsApplied() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch requestReceived = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            requestReceived.countDown();
            try {
                Thread.sleep(500);
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception ignored) {
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            RestClient client = new RestClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "key", Duration.ofMillis(50));

            assertThrows(HttpTimeoutException.class, () -> client.get("/slow"));
            assertTrue(requestReceived.await(1, TimeUnit.SECONDS));
        } finally {
            server.stop(0);
        }
    }
}
