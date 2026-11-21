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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionConcurrencyQuotaTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server(String configJson, List<JsonNode> bodies, CountDownLatch hold) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        if (configJson != null) {
            server.createContext("/api/v1/config", exchange -> {
                byte[] body = configJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
        }
        server.createContext("/api/v1/run/flow/stream", exchange -> {
            bodies.add(MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            if (hold != null) {
                try {
                    assertTrue(hold.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] close = "event: close\ndata: {\"run_finished\":true}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseBody().write(close);
            exchange.getResponseBody().flush();
            exchange.close();
        });
        server.start();
        return server;
    }

    private static TaiyiAgent agent(HttpServer server) {
        return TaiyiAgent.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key").flowId("flow").streamProtocol(StreamProtocol.SSE).build();
    }

    @Test
    void serverQuotaRejectsNewChatAtLimitAndFreesSlotOnCompletion() throws Exception {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        CountDownLatch hold = new CountDownLatch(1);
        HttpServer server = server("{\"client_max_session_concurrency\":1}", bodies, hold);
        TaiyiAgent agent = agent(server);
        try {
            assertEquals(1, agent.getMaxSessionConcurrency());
            AgentSession first = agent.newSession("s1");
            first.chat("a");
            AgentSession second = agent.newSession("s2");
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> second.chat("b"));
            assertTrue(error.getMessage().contains("Maximum concurrent executing sessions reached: 1"));

            hold.countDown();
            assertTrue(first.await(java.time.Duration.ofSeconds(5)));
            second.chat("b");
            assertTrue(second.await(java.time.Duration.ofSeconds(5)));
            assertEquals(2, bodies.size());
        } finally {
            hold.countDown();
            agent.shutdown();
            server.stop(0);
        }
    }

    @Test
    void explicitOverrideWinsOverServerQuotaAndZeroBlocksNewChats() throws Exception {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        CountDownLatch hold = new CountDownLatch(1);
        HttpServer server = server("{\"client_max_session_concurrency\":1}", bodies, hold);
        TaiyiAgent blocked = TaiyiAgent.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key").flowId("flow").streamProtocol(StreamProtocol.SSE)
                .maxSessionConcurrency(0).build();
        TaiyiAgent widened = TaiyiAgent.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key").flowId("flow").streamProtocol(StreamProtocol.SSE)
                .maxSessionConcurrency(2).build();
        try {
            assertEquals(0, blocked.getMaxSessionConcurrency());
            assertThrows(IllegalStateException.class, () -> blocked.newSession("x").chat("a"));

            assertEquals(2, widened.getMaxSessionConcurrency());
            AgentSession first = widened.newSession("s1");
            AgentSession second = widened.newSession("s2");
            first.chat("a");
            second.chat("b");
            hold.countDown();
            assertTrue(first.await(java.time.Duration.ofSeconds(5)));
            assertTrue(second.await(java.time.Duration.ofSeconds(5)));
        } finally {
            hold.countDown();
            blocked.shutdown();
            widened.shutdown();
            server.stop(0);
        }
    }

    @Test
    void configFetchFailureLeavesConcurrencyUnlimited() throws Exception {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        CountDownLatch hold = new CountDownLatch(1);
        HttpServer server = server(null, bodies, hold);
        TaiyiAgent agent = agent(server);
        try {
            assertEquals(-1, agent.getMaxSessionConcurrency());
            AgentSession first = agent.newSession("s1");
            AgentSession second = agent.newSession("s2");
            first.chat("a");
            second.chat("b");
            hold.countDown();
            assertTrue(first.await(java.time.Duration.ofSeconds(5)));
            assertTrue(second.await(java.time.Duration.ofSeconds(5)));
        } finally {
            hold.countDown();
            agent.shutdown();
            server.stop(0);
        }
    }

    @Test
    void sharedWorkspaceOccupancyWarnsWithoutBlocking(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace)
            throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SessionManager.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        CountDownLatch hold = new CountDownLatch(1);
        HttpServer server = server("{\"client_max_session_concurrency\":2}", bodies, hold);
        TaiyiAgent agent = TaiyiAgent.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key").flowId("flow").streamProtocol(StreamProtocol.SSE).workspace(workspace).build();
        try {
            AgentSession first = agent.newSession("s1");
            AgentSession second = agent.newSession("s2");
            first.chat("a");
            second.chat("b");
            assertTrue(appender.list.stream().anyMatch(event ->
                    event.getLevel() == ch.qos.logback.classic.Level.WARN
                            && event.getFormattedMessage().contains("concurrently used")));
            hold.countDown();
            assertTrue(first.await(java.time.Duration.ofSeconds(5)));
            assertTrue(second.await(java.time.Duration.ofSeconds(5)));
            assertEquals(2, bodies.size());
        } finally {
            logger.detachAppender(appender);
            hold.countDown();
            agent.shutdown();
            server.stop(0);
        }
    }

    @Test
    void concurrentChatCannotExceedLimitWhileFirstIsStillPreparing() throws Exception {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch inPreparation = new CountDownLatch(1);
        CountDownLatch preparationRelease = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        HttpServer server = server("{\"client_max_session_concurrency\":1}", bodies, hold);
        TaiyiAgent agent = TaiyiAgent.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key").flowId("flow").streamProtocol(StreamProtocol.SSE)
                .flowAdapter(request -> {
                    if (first.compareAndSet(true, false)) {
                        inPreparation.countDown();
                        try {
                            assertTrue(preparationRelease.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                })
                .build();
        try {
            AgentSession a = agent.newSession("s1");
            Thread starter = new Thread(() -> a.chat("a"));
            starter.start();
            assertTrue(inPreparation.await(5, TimeUnit.SECONDS));
            // s1 has passed the guard but is not CONNECTING yet; its reservation must occupy the slot.
            AgentSession b = agent.newSession("s2");
            assertThrows(IllegalStateException.class, () -> b.chat("b"));
            preparationRelease.countDown();
            starter.join(5000);
            hold.countDown();
            assertTrue(a.await(java.time.Duration.ofSeconds(5)));
            assertEquals(1, bodies.size());
        } finally {
            preparationRelease.countDown();
            hold.countDown();
            agent.shutdown();
            server.stop(0);
        }
    }

    @Test
    void autoLoopRoundsFallBackToServerQuotaUnlessExplicit() throws Exception {
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        HttpServer server = server("{\"client_max_session_concurrency\":3,\"agent_auto_loop_max_rounds\":6}",
                bodies, null);
        TaiyiAgent agent = TaiyiAgent.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key").flowId("flow").streamProtocol(StreamProtocol.SSE)
                .requestDefaults(RequestDefaults.builder().autoLoopEnabled(true).build())
                .build();
        try {
            AgentSession session = agent.newSession("s1");
            session.chat("a");
            assertTrue(session.await(java.time.Duration.ofSeconds(5)));
            assertTrue(bodies.get(0).required("agent_auto_loop_enabled").asBoolean());
            assertEquals(6, bodies.get(0).required("agent_auto_loop_max_rounds").asInt());

            AgentSession explicit = agent.newSession("s2");
            ChatRequest request = new ChatRequest("b", "s2");
            request.setAgentAutoLoopMaxRounds(2);
            explicit.chat(request);
            assertTrue(explicit.await(java.time.Duration.ofSeconds(5)));
            assertEquals(2, bodies.get(1).required("agent_auto_loop_max_rounds").asInt());

            AgentSession disabled = agent.newSession("s3");
            ChatRequest off = new ChatRequest("c", "s3");
            off.setAgentAutoLoopEnabled(false);
            disabled.chat(off);
            assertTrue(disabled.await(java.time.Duration.ofSeconds(5)));
            assertFalse(bodies.get(2).required("agent_auto_loop_enabled").asBoolean());
            assertFalse(bodies.get(2).has("agent_auto_loop_max_rounds"));
        } finally {
            agent.shutdown();
            server.stop(0);
        }
    }
}