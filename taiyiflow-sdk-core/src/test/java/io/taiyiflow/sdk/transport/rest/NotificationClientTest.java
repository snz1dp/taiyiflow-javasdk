package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void recentParsesDeliveryPayloadsWithLimitContract() throws Exception {
        List<String> queries = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agents/client/notifications/recent", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            ObjectNode delivery = MAPPER.createObjectNode()
                    .put("notification_id", "n-1")
                    .put("delivery_id", "d-1")
                    .put("sender_principal_id", "agent-1")
                    .put("target_principal_id", "user-1")
                    .put("intent", "notice")
                    .put("channel_hint", "desktop_ws")
                    .put("category", "approval")
                    .put("level", "info")
                    .put("title", "审批提醒")
                    .put("created_at", "2026-11-21T10:00:00+00:00");
            delivery.set("content", MAPPER.createObjectNode().put("text", "请审批"));
            delivery.set("payload", MAPPER.createObjectNode().put("task_id", "task-1"));
            delivery.set("delivery", MAPPER.createObjectNode()
                    .put("status", "delivered")
                    .put("attempt_count", 2)
                    .putNull("ack_at")
                    .putNull("read_at"));
            ObjectNode response = MAPPER.createObjectNode();
            response.set("notifications", MAPPER.createArrayNode().add(delivery));
            byte[] body = MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            NotificationClient client = new NotificationClient(new RestClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "key", Duration.ofSeconds(5)));

            List<NotificationClient.Delivery> deliveries = client.recent(20);

            assertEquals("limit=20", queries.get(0));
            assertEquals(1, deliveries.size());
            NotificationClient.Delivery delivery = deliveries.get(0);
            assertEquals("n-1", delivery.notificationId());
            assertEquals("d-1", delivery.deliveryId());
            assertEquals("notice", delivery.intent());
            assertEquals("审批提醒", delivery.title());
            assertEquals("请审批", delivery.content().path("text").asText());
            assertEquals("task-1", delivery.payload().path("task_id").asText());
            assertEquals("delivered", delivery.deliveryStatus());
            assertEquals(2, delivery.attemptCount());
            assertFalse(delivery.acknowledged());
            assertFalse(delivery.read());
            assertNull(delivery.expiresAt());
            assertEquals("d-1", delivery.raw().path("delivery_id").asText());

            client.recent();
            assertEquals("limit=100", queries.get(1));
            assertThrows(IllegalArgumentException.class, () -> client.recent(0));
            assertThrows(IllegalArgumentException.class, () -> client.recent(101));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void markReadPostsNotificationIdAndSurfacesMissingNotification() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agents/client/notifications", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            if (exchange.getRequestURI().getRawPath().endsWith("/missing/read")) {
                byte[] body = MAPPER.writeValueAsBytes(MAPPER.createObjectNode().put("detail", "客户端通知不存在或已过期"));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(404, body.length);
                exchange.getResponseBody().write(body);
            } else {
                byte[] body = MAPPER.writeValueAsBytes(MAPPER.createObjectNode()
                        .put("notification_id", "n-1")
                        .put("read_at", "2026-11-21T11:00:00+00:00"));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            NotificationClient client = new NotificationClient(new RestClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "key", Duration.ofSeconds(5)));

            NotificationClient.ReadResult result = client.markRead("n-1");

            assertEquals(List.of("POST /api/v1/agents/client/notifications/n-1/read"), requests);
            assertEquals("n-1", result.notificationId());
            assertEquals("2026-11-21T11:00:00+00:00", result.readAt());

            RestClient.RestException missing = assertThrows(RestClient.RestException.class,
                    () -> client.markRead("missing"));
            assertEquals(404, missing.getStatusCode());
            assertThrows(IllegalArgumentException.class, () -> client.markRead(" "));
            assertTrue(requests.get(1).endsWith("/missing/read"));
        } finally {
            server.stop(0);
        }
    }
}