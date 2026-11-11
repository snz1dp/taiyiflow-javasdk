package io.taiyiflow.sdk.transport.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.transport.rest.RestClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamProtocolResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void autoReadsSseAndCachesResult() throws Exception {
        StubRestClient restClient = new StubRestClient();
        restClient.response = mapper.readTree("{\"stream_protocol\":\"sse\"}");
        StreamProtocolResolver resolver = new StreamProtocolResolver(restClient, StreamProtocol.AUTO);

        assertEquals(StreamProtocol.SSE, resolver.resolve());
        assertEquals(StreamProtocol.SSE, resolver.resolve());
        assertEquals(1, restClient.calls);
    }

    @Test
    void autoRefreshReadsConfigAgain() throws Exception {
        StubRestClient restClient = new StubRestClient();
        restClient.response = mapper.readTree("{\"stream_protocol\":\"sse\"}");
        StreamProtocolResolver resolver = new StreamProtocolResolver(restClient, StreamProtocol.AUTO);

        assertEquals(StreamProtocol.SSE, resolver.resolve());
        restClient.response = mapper.readTree("{\"stream_protocol\":\"websocket\"}");
        assertEquals(StreamProtocol.WEBSOCKET, resolver.refresh());
        assertEquals(StreamProtocol.WEBSOCKET, resolver.resolve());
        assertEquals(2, restClient.calls);
    }

    @Test
    void explicitProtocolRefreshSkipsConfig() {
        StubRestClient restClient = new StubRestClient();
        StreamProtocolResolver resolver = new StreamProtocolResolver(restClient, StreamProtocol.SSE);

        assertEquals(StreamProtocol.SSE, resolver.refresh());
        assertEquals(0, restClient.calls);
    }

    @Test
    void autoReadsWebSocketCaseInsensitively() throws Exception {
        StubRestClient restClient = new StubRestClient();
        restClient.response = mapper.readTree("{\"stream_protocol\":\" WebSocket \"}");
        StreamProtocolResolver resolver = new StreamProtocolResolver(restClient, StreamProtocol.AUTO);

        assertEquals(StreamProtocol.WEBSOCKET, resolver.resolve());
        assertEquals(1, restClient.calls);
    }

    @Test
    void explicitProtocolsSkipConfig() {
        StubRestClient restClient = new StubRestClient();

        assertEquals(StreamProtocol.SSE,
                new StreamProtocolResolver(restClient, StreamProtocol.SSE).resolve());
        assertEquals(StreamProtocol.WEBSOCKET,
                new StreamProtocolResolver(restClient, StreamProtocol.WEBSOCKET).resolve());
        assertEquals(0, restClient.calls);
    }

    @Test
    void configFailureFallsBackToWebSocketAndCaches() {
        StubRestClient restClient = new StubRestClient();
        restClient.failure = new java.io.IOException("offline");
        StreamProtocolResolver resolver = new StreamProtocolResolver(restClient, StreamProtocol.AUTO);

        assertEquals(StreamProtocol.WEBSOCKET, resolver.resolve());
        assertEquals(StreamProtocol.WEBSOCKET, resolver.resolve());
        assertEquals(1, restClient.calls);
    }

    @Test
    void missingOrUnknownProtocolFallsBackToWebSocket() throws Exception {
        StubRestClient missing = new StubRestClient();
        missing.response = mapper.readTree("{}");
        assertEquals(StreamProtocol.WEBSOCKET,
                new StreamProtocolResolver(missing, StreamProtocol.AUTO).resolve());

        StubRestClient unknown = new StubRestClient();
        unknown.response = mapper.readTree("{\"stream_protocol\":\"http\"}");
        assertEquals(StreamProtocol.WEBSOCKET,
                new StreamProtocolResolver(unknown, StreamProtocol.AUTO).resolve());
    }

    private static final class StubRestClient extends RestClient {
        private JsonNode response;
        private Exception failure;
        private int calls;

        private StubRestClient() {
            super("http://localhost", "key", Duration.ofSeconds(1));
        }

        @Override
        public JsonNode get(String path) throws Exception {
            if (!"/api/v1/config".equals(path)) {
                throw new AssertionError("Unexpected path: " + path);
            }
            calls++;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
