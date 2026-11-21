package io.taiyiflow.sdk.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.RequestDefaults;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentSessionRequestDefaultsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path temp;

    private HttpServer capturingServer(List<JsonNode> bodies) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/run/flow/stream", exchange -> {
            bodies.add(MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = "event: close\ndata: {\"run_finished\":true}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static CountDownLatch closedLatch(AgentSession session) {
        CountDownLatch closed = new CountDownLatch(1);
        session.onStream(new io.taiyiflow.sdk.hooks.StreamHook() {
            @Override public void onClose(String sessionId, String reason) { closed.countDown(); }
        });
        return closed;
    }

    @Test
    void firstFrameCarriesDefaultsAndExplicitInstructions() throws Exception {
        Files.writeString(temp.resolve("AGENTS.md"), "project rule");
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = capturingServer(bodies);
        TaiyiAgent agent = TaiyiAgent.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key").flowId("flow").streamProtocol(StreamProtocol.SSE)
                .requestDefaults(RequestDefaults.builder().model("qwen-max").build())
                .instructionDirectories(List.of(temp))
                .build();
        try {
            AgentSession session = agent.newSession("session");
            CountDownLatch closed = closedLatch(session);
            session.chat("hello");
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            JsonNode sent = bodies.get(0);
            assertEquals("chat", sent.required("input_type").asText());
            assertEquals("chat", sent.required("output_type").asText());
            assertFalse(sent.required("agent_auto_loop_enabled").asBoolean());
            assertEquals("qwen-max", sent.required("model").asText());
            assertFalse(sent.has("image_generation_enabled"));
            String merged = sent.required("client_instructions").required("merged").asText();
            assertTrue(merged.contains("[Instructions from AGENTS.md (project, priority 20)]\nproject rule"));
            assertFalse(merged.contains(temp.toAbsolutePath().toString()));
        } finally {
            agent.shutdown();
            server.stop(0);
        }
    }

    @Test
    void instructionsDisabledByDefaultAndPerRequestPackageWins() throws Exception {
        Files.writeString(temp.resolve("AGENTS.md"), "project rule");
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = capturingServer(bodies);
        TaiyiAgent agent = TaiyiAgent.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key").flowId("flow").streamProtocol(StreamProtocol.SSE)
                .build();
        try {
            AgentSession session = agent.newSession("session");
            CountDownLatch closed = closedLatch(session);
            session.chat("hello");
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertFalse(bodies.get(0).has("client_instructions"));

            AgentSession explicit = agent.newSession("session-2");
            CountDownLatch closed2 = closedLatch(explicit);
            ChatRequest request = new ChatRequest("hello", "session-2");
            request.setClientInstructions(MAPPER.readTree("{\"layers\":[],\"merged\":\"explicit\"}"));
            explicit.chat(request);
            assertTrue(closed2.await(5, TimeUnit.SECONDS));
            assertEquals("explicit", bodies.get(1).required("client_instructions").required("merged").asText());
        } finally {
            agent.shutdown();
            server.stop(0);
        }
    }

    @Test
    void clientTypeDefaultsToDeviceAndHonorsOverrides() throws Exception {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = capturingServer(bodies);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        TaiyiAgent defaultAgent = TaiyiAgent.builder().baseUrl(baseUrl)
                .apiKey("key").flowId("flow").streamProtocol(StreamProtocol.SSE).build();
        TaiyiAgent desktopAgent = TaiyiAgent.builder().baseUrl(baseUrl)
                .apiKey("key").flowId("flow").streamProtocol(StreamProtocol.SSE).clientType("desktop").build();
        try {
            assertEquals("device", defaultAgent.getClientType());
            AgentSession first = defaultAgent.newSession("s1");
            first.chat("a");
            assertTrue(first.await(java.time.Duration.ofSeconds(5)));
            assertEquals("device", bodies.get(0).required("client_type").asText());

            AgentSession second = desktopAgent.newSession("s2");
            second.chat("b");
            assertTrue(second.await(java.time.Duration.ofSeconds(5)));
            assertEquals("desktop", bodies.get(1).required("client_type").asText());

            AgentSession third = desktopAgent.newSession("s3");
            ChatRequest request = new ChatRequest("c", "s3");
            request.setClientType("custom");
            third.chat(request);
            assertTrue(third.await(java.time.Duration.ofSeconds(5)));
            assertEquals("custom", bodies.get(2).required("client_type").asText());
        } finally {
            defaultAgent.shutdown();
            desktopAgent.shutdown();
            server.stop(0);
        }
    }

    @Test
    void retryReusesRequestOptionsSnapshot() throws Exception {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = capturingServer(bodies);
        TaiyiAgent agent = TaiyiAgent.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key").flowId("flow").streamProtocol(StreamProtocol.SSE)
                .requestDefaults(RequestDefaults.builder()
                        .model("qwen-max")
                        .groupId("group-1")
                        .relationSessions(List.of("related-1"))
                        .imageGeneration(RequestDefaults.ImageGeneration.ON)
                        .build())
                .build();
        try {
            AgentSession session = agent.newSession("session");
            CountDownLatch closed = closedLatch(session);
            session.chat("hello");
            assertTrue(closed.await(5, TimeUnit.SECONDS));

            CountDownLatch retried = closedLatch(session);
            session.retry();
            assertTrue(retried.await(5, TimeUnit.SECONDS));
            JsonNode second = bodies.get(1);
            assertEquals("qwen-max", second.required("model").asText());
            assertEquals("group-1", second.required("group_id").asText());
            assertEquals("related-1", second.required("relation_sessions").get(0).asText());
            assertTrue(second.required("image_generation_enabled").asBoolean());
            assertEquals("chat", second.required("input_type").asText());
            assertFalse(second.required("agent_auto_loop_enabled").asBoolean());
        } finally {
            agent.shutdown();
            server.stop(0);
        }
    }
}