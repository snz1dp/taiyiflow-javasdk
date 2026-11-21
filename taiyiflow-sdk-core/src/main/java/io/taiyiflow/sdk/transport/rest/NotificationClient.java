package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Notification REST fallback for restart unread pull
 * (GET /api/v1/agents/client/notifications/recent, POST /api/v1/agents/client/notifications/{id}/read).
 */
public class NotificationClient {
    private static final String BASE_PATH = "/api/v1/agents/client/notifications";

    private final RestClient rest;

    public NotificationClient(RestClient rest) {
        this.rest = rest;
    }

    /** Delivery payload as built by the platform; the full server record stays available via {@link #raw()}. */
    public record Delivery(String notificationId, String deliveryId, String senderPrincipalId,
                           String targetPrincipalId, String intent, String channelHint,
                           String correlationId, String dedupKey, JsonNode payload, String category,
                           String level, String title, JsonNode content, String createdAt,
                           String expiresAt, JsonNode metadata, String deliveryStatus,
                           Integer attemptCount, String ackAt, String readAt, String ackKind,
                           String playAt, JsonNode raw) {

        public boolean acknowledged() {
            return ackAt != null;
        }

        public boolean read() {
            return readAt != null;
        }

        public static Delivery from(JsonNode node) {
            JsonNode delivery = node.path("delivery");
            return new Delivery(
                    text(node, "notification_id"), text(node, "delivery_id"),
                    text(node, "sender_principal_id"), text(node, "target_principal_id"),
                    text(node, "intent"), text(node, "channel_hint"),
                    text(node, "correlation_id"), text(node, "dedup_key"),
                    node.get("payload"), text(node, "category"), text(node, "level"),
                    text(node, "title"), node.get("content"), text(node, "created_at"),
                    text(node, "expires_at"), node.get("metadata"),
                    text(delivery, "status"),
                    delivery.hasNonNull("attempt_count") ? delivery.get("attempt_count").asInt() : null,
                    text(delivery, "ack_at"), text(delivery, "read_at"),
                    text(delivery, "ack_kind"), text(delivery, "play_at"), node);
        }

        private static String text(JsonNode node, String field) {
            return node.hasNonNull(field) ? node.get(field).asText() : null;
        }
    }

    public record ReadResult(String notificationId, String readAt) {
    }

    public List<Delivery> recent() throws Exception {
        return recent(100);
    }

    public List<Delivery> recent(int limit) throws Exception {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be within 1..100");
        }
        JsonNode response = rest.get(RestClient.appendQuery(BASE_PATH + "/recent", Map.of("limit", limit)));
        List<Delivery> deliveries = new ArrayList<>();
        for (JsonNode node : response.path("notifications")) {
            deliveries.add(Delivery.from(node));
        }
        return List.copyOf(deliveries);
    }

    /** Marks the notification read by notification_id (not delivery_id); 404 when missing or expired. */
    public ReadResult markRead(String notificationId) throws Exception {
        if (notificationId == null || notificationId.isBlank()) {
            throw new IllegalArgumentException("notificationId is required");
        }
        JsonNode response = rest.post(BASE_PATH + "/" + RestClient.pathSegment(notificationId) + "/read", null);
        return new ReadResult(response.path("notification_id").asText(notificationId),
                response.path("read_at").asText(null));
    }
}