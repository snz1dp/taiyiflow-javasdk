package io.taiyiflow.sdk.plugins.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import io.taiyiflow.sdk.transport.rest.FlowRunClient;
import io.taiyiflow.sdk.transport.rest.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaUnderstandingPluginTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void routesBusinessUploadsToDefaultUnderstandingFlows() throws Exception {
        Files.writeString(workspace.resolve("doc.pdf"), "document");
        Files.writeString(workspace.resolve("image.png"), "image");
        Files.writeString(workspace.resolve("audio.mp3"), "audio");
        Files.writeString(workspace.resolve("video.mp4"), "video");
        try (TestPlatform platform = new TestPlatform(false)) {
            PluginManager plugins = new PluginManager();
            MediaUnderstandingPlugin plugin = new MediaUnderstandingPlugin(platform.rest(), Duration.ofSeconds(2),
                    pollOptions(Duration.ofSeconds(2)), pollOptions(Duration.ofSeconds(2)));
            plugin.register(plugins);
            assertEquals("document_understanding",
                    plugins.getDefinition("document_unstructured").getRequiredFeature());
            assertEquals("image_understanding",
                    plugins.getDefinition("image_understanding").getRequiredFeature());
            assertEquals("audio_understanding",
                    plugins.getDefinition("audio_understanding").getRequiredFeature());
            assertEquals("video_understanding",
                    plugins.getDefinition("video_understanding").getRequiredFeature());
            ToolExecutionContext context = context(ToolExecutionContext.Cancellation.none());

            ObjectNode documentArguments = arguments("doc.pdf");
            documentArguments.put("flow_id", "forged-flow");
            documentArguments.put("session_id", "forged-session");
            documentArguments.put("input_type", "text");
            documentArguments.put("output_type", "json");
            ToolResult document = plugins.execute(context,
                    new ToolCall("doc-call", "document_unstructured", documentArguments));
            assertTrue(document.isSuccess(), document.getError());
            assertEquals(1, document.getResult().path("count").asInt());
            assertEquals("doc", document.getResult().path("items").get(0).path("text").asText());

            ToolResult image = plugins.execute(context,
                    new ToolCall("image-call", "image_understanding", arguments("image.png")));
            ToolResult audio = plugins.execute(context,
                    new ToolCall("audio-call", "audio_understanding", arguments("audio.mp3")));
            ToolResult video = plugins.execute(context,
                    new ToolCall("video-call", "video_understanding", arguments("video.mp4")));
            assertEquals("image result", image.getResult().path("text").asText());
            assertEquals("audio result", audio.getResult().path("text").asText());
            assertEquals("video result", video.getResult().path("text").asText());

            ObjectNode screenshotArguments = arguments("image.png");
            screenshotArguments.put("screenshot_width_px", 1280);
            screenshotArguments.put("screenshot_height_px", 720);
            screenshotArguments.put("display_origin_x", 10);
            String screenshot = plugin.understandScreenshot(context, screenshotArguments);
            assertEquals("image result", screenshot);

            assertEquals(5, platform.uploads.get());
            assertEquals(5, platform.uploadSessions.stream().filter("session-1"::equals).count());
            JsonNode documentRun = platform.runs.get("doc-flow");
            assertFalse(documentRun.path("async").asBoolean());
            assertEquals("text", documentRun.path("input_type").asText());
            assertEquals("json", documentRun.path("output_type").asText());
            assertEquals("session-1", documentRun.path("session_id").asText());
            assertEquals("/uploads/doc.pdf", documentRun.path("files").get(0).asText());

            JsonNode imageRun = platform.imageRuns.get(0);
            assertTrue(imageRun.path("async").asBoolean());
            assertEquals("text", imageRun.path("tweaks").path("TextOutput-fXIUJ")
                    .path("output_format").asText());
            assertFalse(imageRun.path("tweaks").has("Prompt-d2zsV"));
            assertFalse(platform.runs.containsKey("business-flow"));
            assertFalse(platform.runs.containsKey("forged-flow"));

            JsonNode screenshotRun = platform.imageRuns.get(1);
            assertEquals("passthrough", screenshotRun.path("tweaks")
                    .path("image_compression_strategy").asText());
            assertTrue(screenshotRun.path("tweaks").has("Prompt-d2zsV"));
            assertTrue(screenshotRun.path("tweaks").has("Prompt-GRXIq"));
            assertTrue(screenshotRun.path("input_value").asText().contains("1280 x 720"));
            assertTrue(screenshotRun.path("input_value").asText().contains("display_origin_x=10"));
            assertFalse(platform.audioRuns.get(0).has("tweaks"));
            assertFalse(platform.videoRuns.get(0).has("tweaks"));
        }
    }

    @Test
    void rejectsMixedDocumentInputBeforeUpload() throws Exception {
        Files.writeString(workspace.resolve("doc.pdf"), "document");
        Files.writeString(workspace.resolve("image.png"), "image");
        try (TestPlatform platform = new TestPlatform(false)) {
            PluginManager plugins = new PluginManager();
            MediaUnderstandingPlugin.register(plugins, platform.rest());
            ObjectNode arguments = MAPPER.createObjectNode();
            arguments.putArray("paths").add("doc.pdf").add("image.png");
            ToolResult result = plugins.execute(context(ToolExecutionContext.Cancellation.none()),
                    new ToolCall("call", "document_unstructured", arguments));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("image_understanding"));
            assertEquals(0, platform.uploads.get());
        }
    }

    @Test
    void cancellationAndTimeoutRequestPlatformCancellation() throws Exception {
        Files.writeString(workspace.resolve("image.png"), "image");
        try (TestPlatform platform = new TestPlatform(true)) {
            PluginManager plugins = new PluginManager();
            MediaUnderstandingPlugin plugin = new MediaUnderstandingPlugin(platform.rest(), Duration.ofSeconds(1),
                    pollOptions(Duration.ofMillis(30)), pollOptions(Duration.ofSeconds(1)));
            plugin.register(plugins);
            ToolExecutionContext.CancellationSource cancellation = new ToolExecutionContext.CancellationSource();
            cancellation.cancel();
            ToolResult cancelled = plugins.execute(context(cancellation),
                    new ToolCall("cancel", "image_understanding", arguments("image.png")));
            assertFalse(cancelled.isSuccess());
            assertTrue(cancelled.getError().contains("cancelled"), cancelled.getError());
            assertEquals(1, platform.cancellations.get());

            ToolResult timedOut = plugins.execute(context(ToolExecutionContext.Cancellation.none()),
                    new ToolCall("timeout", "image_understanding", arguments("image.png")));
            assertFalse(timedOut.isSuccess());
            assertTrue(timedOut.getError().contains("timeout"));
            assertEquals(2, platform.cancellations.get());
        }
    }

    private ToolExecutionContext context(ToolExecutionContext.Cancellation cancellation) {
        return ToolExecutionContext.builder("business-flow", "session-1")
                .workspace(workspace).cancellation(cancellation).build();
    }

    private static ObjectNode arguments(String path) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("path", path);
        result.put("input", "describe");
        return result;
    }

    private static FlowRunClient.PollOptions pollOptions(Duration timeout) {
        return new FlowRunClient.PollOptions(Duration.ofSeconds(1), Duration.ofMillis(5), timeout);
    }

    private static final class TestPlatform implements AutoCloseable {
        private final HttpServer server;
        private final boolean keepRunning;
        private final Map<String, JsonNode> runs = new ConcurrentHashMap<>();
        private final List<JsonNode> imageRuns = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<JsonNode> audioRuns = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<JsonNode> videoRuns = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<String> uploadSessions = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger uploads = new AtomicInteger();
        private final AtomicInteger tasks = new AtomicInteger();
        private final AtomicInteger cancellations = new AtomicInteger();
        private final java.util.Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();

        private TestPlatform(boolean keepRunning) throws IOException {
            this.keepRunning = keepRunning;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/config", exchange -> respond(exchange, 200, MAPPER.createObjectNode()
                    .put("doc_unstructured_flow_id", "doc-flow")
                    .put("image_flow_id", "image-flow")
                    .put("audio_flow_id", "audio-flow")
                    .put("video_flow_id", "video-flow")
                    .put("max_file_size_upload", 1)
                    .put("once_max_file_count", 5)
                    .set("doc_exts", MAPPER.createArrayNode().add(".pdf").add("docx"))));
            server.createContext("/api/v1/agents/upload/business-flow", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String session = body.contains("session-1") ? "session-1" : "forged-session";
                uploadSessions.add(session);
                String filename = body.contains("doc.pdf") ? "doc.pdf"
                        : body.contains("image.png") ? "image.png"
                        : body.contains("audio.mp3") ? "audio.mp3" : "video.mp4";
                uploads.incrementAndGet();
                respond(exchange, 200, MAPPER.createObjectNode()
                        .put("flow_id", "business-flow").put("file_path", "/uploads/" + filename));
            });
            server.createContext("/api/v1/run/", this::run);
            server.start();
        }

        private RestClient rest() {
            return new RestClient("http://127.0.0.1:" + server.getAddress().getPort(), "key",
                    Duration.ofSeconds(2));
        }

        private void run(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            if (path.contains("/tasks/")) {
                if (path.endsWith("/cancel")) {
                    cancellations.incrementAndGet();
                    String taskId = path.substring(path.lastIndexOf("/tasks/") + 7, path.lastIndexOf("/cancel"));
                    cancelledTasks.add(taskId);
                    respond(exchange, 202, MAPPER.createObjectNode()
                            .put("task_id", taskId).put("status", "cancelled"));
                    return;
                }
                String taskId = path.substring(path.lastIndexOf('/') + 1);
                if (keepRunning) {
                    respond(exchange, 200, MAPPER.createObjectNode()
                            .put("task_id", taskId)
                            .put("status", cancelledTasks.contains(taskId) ? "cancelled" : "running"));
                    return;
                }
                String media = taskId.split("-", 3)[1];
                respond(exchange, 200, success(taskId, media + " result"));
                return;
            }
            String flow = path.substring(path.lastIndexOf('/') + 1);
            runs.put(flow, body);
            if ("doc-flow".equals(flow)) {
                ObjectNode parsed = MAPPER.createObjectNode();
                parsed.putArray("items").addObject().put("text", "doc");
                ObjectNode runResponse = MAPPER.createObjectNode()
                        .put("session_id", "session-1").put("media_type", "application/json");
                runResponse.putArray("outputs").add(MAPPER.writeValueAsString(parsed));
                respond(exchange, 200, runResponse);
                return;
            }
            String media = flow.substring(0, flow.indexOf('-'));
            if ("image".equals(media)) imageRuns.add(body);
            if ("audio".equals(media)) audioRuns.add(body);
            if ("video".equals(media)) videoRuns.add(body);
            String taskId = "task-" + media + "-" + tasks.incrementAndGet();
            respond(exchange, 202, MAPPER.createObjectNode().put("task_id", taskId).put("status", "pending"));
        }

        private static ObjectNode success(String taskId, String output) {
            ObjectNode result = MAPPER.createObjectNode()
                    .put("session_id", "session-1").put("media_type", "text/plain");
            result.putArray("outputs").add(output);
            return MAPPER.createObjectNode().put("task_id", taskId).put("status", "success")
                    .put("session_id", "session-1").set("result", result);
        }

        private static void respond(HttpExchange exchange, int status, JsonNode body) throws IOException {
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
