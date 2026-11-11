package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.transport.rest.FileClient;
import io.taiyiflow.sdk.worker.auth.WorkerAuth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigitalWorkerPlatformClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<RequestRecord> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String path = exchange.getRequestURI().getPath();
            requests.add(new RequestRecord(path, authorization, body));
            int status = "Bearer old-token".equals(authorization) ? 401 : 200;
            String response = path.startsWith("/api/v1/agents/upload/")
                    ? "{\"flow_id\":\"flow-1\",\"file_path\":\"uploads/report.txt\"}" : "{}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void memoryAndArtifactRequestsRefreshTokenWithoutLocalResultStorage(@TempDir Path workspace) throws Exception {
        RefreshingAuth auth = new RefreshingAuth();
        auth.authenticate(baseUrl);
        DigitalWorker worker = DigitalWorker.builder()
                .baseUrl(baseUrl)
                .auth(auth)
                .identity(WorkerIdentity.of("worker-1", "Worker"))
                .workspace(workspace)
                .identityValidator((identity, role, abilities) -> { })
                .build();
        Path artifact = workspace.resolve("report.txt");
        Files.writeString(artifact, "server result");

        worker.memories().create(mapper.createObjectNode().put("content", "remember"));
        FileClient.UploadedFile uploaded = worker.uploadArtifact("flow-1", "session-1", artifact);
        worker.stop();

        assertEquals("uploads/report.txt", uploaded.filePath());
        assertEquals(3, requests.size());
        assertEquals("Bearer old-token", requests.get(0).authorization);
        assertEquals("Bearer new-token", requests.get(1).authorization);
        assertEquals("Bearer new-token", requests.get(2).authorization);
        assertEquals("/api/v1/agents/memories", requests.get(1).path);
        assertTrue(requests.get(1).body.contains("\"content\":\"remember\""));
        assertEquals("/api/v1/agents/upload/flow-1", requests.get(2).path);
        assertTrue(requests.get(2).body.contains("session-1"));
        assertTrue(requests.get(2).body.contains("server result"));
    }

    private static final class RefreshingAuth extends WorkerAuth {
        private volatile String token = "old-token";

        @Override
        public String accessToken() {
            return token;
        }

        @Override
        protected void doAuthenticate(String baseUrl) {
        }

        @Override
        protected boolean doRefresh() {
            token = "new-token";
            return true;
        }

        @Override
        protected boolean supportsRefresh() {
            return true;
        }
    }

    private record RequestRecord(String path, String authorization, String body) {
    }
}
