package io.taiyiflow.sdk.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.skill.SkillRegistry;
import io.taiyiflow.sdk.core.skill.SkillSource;
import io.taiyiflow.sdk.core.skill.SkillSupplier;
import io.taiyiflow.sdk.core.skill.SkillSupplyPolicy;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentSessionSkillSupplyTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path temp;

    @Test
    void firstFrameContainsSuppliedSkillsAndRunContextRetainsThem() throws Exception {
        Path skills = temp.resolve("skills");
        Files.createDirectories(skills.resolve("demo"));
        Files.writeString(skills.resolve("demo/SKILL.md"), "---\nname: demo\n---\nfull body");
        AtomicReference<JsonNode> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/run/flow/stream", exchange -> {
            body.set(MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = "event: close\ndata: {\"run_finished\":true}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        TaiyiAgent agent = TaiyiAgent.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key").flowId("flow").streamProtocol(StreamProtocol.SSE)
                .skillSupplier(new SkillSupplier(SkillRegistry.builder()
                        .directory(skills, SkillSource.WORKSPACE).build(), SkillSupplyPolicy.localOnly()), List.of())
                .build();
        try {
            AgentSession session = agent.newSession("session");
            CountDownLatch closed = new CountDownLatch(1);
            session.onStream(new io.taiyiflow.sdk.hooks.StreamHook() {
                @Override public void onClose(String sessionId, String reason) { closed.countDown(); }
            });
            ChatRequest request = new ChatRequest("hello", "session");
            request.setRequestedSkillNames(List.of("demo"));
            session.chat(request);
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            JsonNode sent = body.get();
            assertEquals("demo", sent.required("requested_skill_names").get(0).asText());
            JsonNode skill = sent.required("client_skill_packages").get(0).get("skills").get(0);
            assertEquals("full body", skill.required("content").asText().trim());
            assertFalse(skill.has("path"));
            assertEquals("demo", ((AgentSessionImpl) session).getRunContext().getSkillSupply()
                    .requestedSkillNames().get(0));
            assertEquals(1, ((AgentSessionImpl) session).getRunContext().getSkillSupply().packages().size());
        } finally {
            agent.shutdown();
            server.stop(0);
        }
    }
}
