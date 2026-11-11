package io.taiyiflow.sdk.transport.stream;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StreamClientTest {

    @Test
    void wsUrlFromHttps() throws Exception {
        StreamClient client = new StreamClient("https://api.example.com", "key", "flow-1");
        var method = StreamClient.class.getDeclaredMethod("buildWebSocketUrl");
        method.setAccessible(true);
        String url = (String) method.invoke(client);
        assertEquals("wss://api.example.com/api/v1/run/flow-1/websocket?__TOKEN__=key", url);
    }

    @Test
    void wsUrlFromHttp() throws Exception {
        StreamClient client = new StreamClient("http://localhost:8080", "key", "flow-2");
        var method = StreamClient.class.getDeclaredMethod("buildWebSocketUrl");
        method.setAccessible(true);
        String url = (String) method.invoke(client);
        assertEquals("ws://localhost:8080/api/v1/run/flow-2/websocket?__TOKEN__=key", url);
    }

    @Test
    void wsUrlFromBareHost() throws Exception {
        StreamClient client = new StreamClient("api.example.com", "key", "flow-3");
        var method = StreamClient.class.getDeclaredMethod("buildWebSocketUrl");
        method.setAccessible(true);
        String url = (String) method.invoke(client);
        assertEquals("wss://api.example.com/api/v1/run/flow-3/websocket?__TOKEN__=key", url);
    }

    @Test
    void wsUrlAlreadyWs() throws Exception {
        StreamClient client = new StreamClient("ws://localhost:9000", "key", "flow-4");
        var method = StreamClient.class.getDeclaredMethod("buildWebSocketUrl");
        method.setAccessible(true);
        String url = (String) method.invoke(client);
        assertEquals("ws://localhost:9000/api/v1/run/flow-4/websocket?__TOKEN__=key", url);
    }

    @Test
    void wsUrlReadsLatestDynamicToken() throws Exception {
        AtomicReference<String> token = new AtomicReference<>("old-token");
        StreamClient client = new StreamClient("http://localhost:8080", token::get, "flow-1", Map.of());
        var method = StreamClient.class.getDeclaredMethod("buildWebSocketUrl");
        method.setAccessible(true);

        assertTrue(((String) method.invoke(client)).endsWith("__TOKEN__=old-token"));
        token.set("new-token");
        assertTrue(((String) method.invoke(client)).endsWith("__TOKEN__=new-token"));
    }

    @Test
    void wsUrlEncodesTokenAndAvoidsDoubleSlash() throws Exception {
        StreamClient client = new StreamClient("http://localhost:8080/", "key +/&=", "flow-5");
        var method = StreamClient.class.getDeclaredMethod("buildWebSocketUrl");
        method.setAccessible(true);
        String url = (String) method.invoke(client);
        assertEquals("ws://localhost:8080/api/v1/run/flow-5/websocket?__TOKEN__=key+%2B%2F%26%3D", url);
    }
}
