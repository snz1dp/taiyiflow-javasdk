package io.taiyiflow.sdk.transport.rest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileClientTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadsMultipartAndDownloadsBytes() throws Exception {
        Path input = tempDir.resolve("hello world.txt");
        Files.writeString(input, "hello 太乙");
        AtomicReference<String> multipartContentType = new AtomicReference<>();
        AtomicReference<String> multipartBody = new AtomicReference<>();
        AtomicReference<String> downloadPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agents/upload/flow-1", exchange -> {
            multipartContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            multipartBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"flow_id\":\"flow-1\",\"file_path\":\"uploads/hello world.txt\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/api/v1/agents/download/", exchange -> {
            downloadPath.set(exchange.getRequestURI().getRawPath());
            byte[] response = new byte[]{0, 1, 2, (byte) 255};
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        RestClient rest = new RestClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key", Duration.ofSeconds(5));
        FileClient client = new FileClient(rest);
        try {
            FileClient.UploadedFile uploaded = client.upload("flow-1", "session-1", input);
            assertEquals("flow-1", uploaded.flowId());
            assertEquals("uploads/hello world.txt", uploaded.filePath());
            assertTrue(multipartContentType.get().startsWith("multipart/form-data; boundary=taiyiflow-"));
            assertTrue(multipartBody.get().contains("name=\"session_id\""));
            assertTrue(multipartBody.get().contains("session-1"));
            assertTrue(multipartBody.get().contains("name=\"file\"; filename=\"hello world.txt\""));
            assertTrue(multipartBody.get().contains("hello 太乙"));

            byte[] downloaded = client.download("flow-1", "session-1", "folder/hello world.txt");
            assertArrayEquals(new byte[]{0, 1, 2, (byte) 255}, downloaded);
            assertEquals("/api/v1/agents/download/flow-1/session-1/folder/hello%20world.txt", downloadPath.get());
        } finally {
            server.stop(0);
        }
    }
}
