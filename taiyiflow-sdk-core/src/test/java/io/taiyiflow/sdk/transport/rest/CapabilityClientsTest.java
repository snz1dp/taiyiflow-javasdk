package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import io.taiyiflow.sdk.transport.envelope.FlowMetadata;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityClientsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void typedConfigAndForwardCompatibleMetadata() throws Exception {
        ServiceConfig config = new ServiceConfig(MAPPER.readTree("{\"default_flow_id\":\" default \","
                + "\"doc_unstructured_flow_id\":\"doc\",\"image_flow_id\":\"image\","
                + "\"audio_flow_id\":\"audio\",\"video_flow_id\":\"video\",\"doc_exts\":[\"PDF\",\".txt\"],"
                + "\"max_file_size_upload\":2,\"once_max_file_count\":3}"));
        assertEquals("default", config.defaultFlowId());
        assertEquals(2L * 1024 * 1024, config.maxFileSizeBytes());
        assertEquals(List.of("pdf", "txt"), config.documentExtensions());

        FlowMetadata metadata = new FlowMetadata(MAPPER.readTree("{\"id\":\"flow\",\"owner_appid\":\"app\","
                + "\"name\":\"Agent\",\"features\":[\"image_understanding\",\"future_feature\"],"
                + "\"knowledges\":[{\"code\":\"k\",\"name\":\"Knowledge\",\"type\":\"shared\"}],"
                + "\"shadow_flows\":[{\"id\":\"shadow\"}],\"custom_fields\":{\"secret\":\"not-logged\"},"
                + "\"future_field\":{\"enabled\":true}}"));
        assertTrue(metadata.supports("future_feature"));
        assertFalse(metadata.supports("video_understanding"));
        assertEquals("Knowledge", metadata.getKnowledges().get(0).name());
        assertEquals("shadow", metadata.getShadowFlows().get(0).raw().path("id").asText());
        assertTrue(metadata.getAdditionalProperties().containsKey("future_field"));
        assertEquals("Agent", metadata.raw().path("name").asText());
    }

    @Test
    void missingDefaultFlowHasNoLocalFallback() throws Exception {
        ServiceConfig config = new ServiceConfig(MAPPER.createObjectNode());
        IllegalStateException error = assertThrows(IllegalStateException.class, config::defaultFlowId);
        assertTrue(error.getMessage().contains("default_flow_id"));
    }

    @Test
    void synchronousAndAsynchronousRunContracts() throws Exception {
        AtomicReference<String> runBody = new AtomicReference<>();
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/run/flow name", exchange -> {
            runBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            boolean async = runBody.get().contains("\"async\":true");
            String response = async ? "{\"task_id\":\"task-1\",\"status\":\"pending\"}"
                    : "{\"session_id\":\"session-1\",\"outputs\":[\"done\"],\"media_type\":\"text/plain\"}";
            respond(exchange, async ? 202 : 200, response);
        });
        server.createContext("/api/v1/run/tasks/task-1", exchange -> {
            String response = polls.incrementAndGet() == 1
                    ? "{\"task_id\":\"task-1\",\"status\":\"running\"}"
                    : "{\"task_id\":\"task-1\",\"status\":\"success\",\"result\":{"
                    + "\"session_id\":\"session-1\",\"outputs\":[\"done\"],\"media_type\":\"text/plain\"}}";
            respond(exchange, 200, response);
        });
        server.start();
        try {
            RestClient rest = new RestClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "key", Duration.ofSeconds(2));
            FlowRunClient client = new FlowRunClient(rest);
            FlowRunClient.RunResult result = client.run("flow name", new ChatRequest("hello", "session-1"));
            assertEquals(List.of("done"), result.outputs());
            assertTrue(runBody.get().contains("\"async\":false"));

            FlowRunClient.Task task = client.submitAsync("flow name", new ChatRequest("hello", "session-1"));
            assertEquals("task-1", task.taskId());
            FlowRunClient.Task completed = client.await(task.taskId(),
                    new FlowRunClient.PollOptions(Duration.ofSeconds(1), Duration.ofMillis(1), Duration.ofSeconds(2)),
                    () -> false);
            assertEquals("success", completed.status());
            assertEquals(List.of("done"), completed.result().outputs());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void submissionWithoutAcceptedTaskIsUnknownAndNeverRetried() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/run/flow", exchange -> {
            submissions.incrementAndGet();
            respond(exchange, 200, "{\"outputs\":[\"accepted but not asynchronous\"]}");
        });
        server.start();
        try {
            FlowRunClient client = new FlowRunClient(new RestClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "key", Duration.ofSeconds(2)));
            assertThrows(FlowRunClient.SubmissionUnknownException.class,
                    () -> client.submitAsync("flow", new ChatRequest("hello", "session-1")));
            assertEquals(1, submissions.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void plainTextRunDoesNotRequireJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/run/flow", exchange -> {
            byte[] response = "plain result".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            FlowRunClient client = new FlowRunClient(new RestClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "key", Duration.ofSeconds(2)));
            FlowRunClient.RunResult result = client.run("flow", new ChatRequest("hello", "s"));
            assertEquals("plain result", result.rawBody());
            assertNull(result.json());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("X-Session-Id", "session-1");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
