package io.taiyiflow.sdk.transport.stream;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.transport.rest.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;

public final class StreamProtocolResolver {

    private static final Logger log = LoggerFactory.getLogger(StreamProtocolResolver.class);

    private final RestClient restClient;
    private final StreamProtocol configuredProtocol;
    private volatile StreamProtocol resolvedProtocol;

    public StreamProtocolResolver(RestClient restClient, StreamProtocol configuredProtocol) {
        this.restClient = restClient;
        this.configuredProtocol = Objects.requireNonNull(configuredProtocol, "configuredProtocol");
        this.resolvedProtocol = configuredProtocol;
    }

    public StreamProtocol resolve() {
        StreamProtocol current = resolvedProtocol;
        if (current != StreamProtocol.AUTO) {
            return current;
        }
        synchronized (this) {
            if (resolvedProtocol != StreamProtocol.AUTO) {
                return resolvedProtocol;
            }
            resolvedProtocol = readProtocol();
            return resolvedProtocol;
        }
    }

    public synchronized StreamProtocol refresh() {
        if (configuredProtocol != StreamProtocol.AUTO) {
            return configuredProtocol;
        }
        resolvedProtocol = readProtocol();
        return resolvedProtocol;
    }

    private StreamProtocol readProtocol() {
        try {
            JsonNode config = restClient.get("/api/v1/config");
            JsonNode protocol = config == null ? null : config.get("stream_protocol");
            return parse(protocol == null ? null : protocol.asText());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while reading stream protocol; falling back to WebSocket");
            return StreamProtocol.WEBSOCKET;
        } catch (Exception e) {
            log.warn("Failed to read stream protocol; falling back to WebSocket: {}", e.getMessage());
            return StreamProtocol.WEBSOCKET;
        }
    }

    private StreamProtocol parse(String value) {
        if (value != null) {
            switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "sse" -> {
                    return StreamProtocol.SSE;
                }
                case "websocket" -> {
                    return StreamProtocol.WEBSOCKET;
                }
                default -> log.warn("Unknown stream protocol '{}'; falling back to WebSocket", value);
            }
        }
        return StreamProtocol.WEBSOCKET;
    }
}
