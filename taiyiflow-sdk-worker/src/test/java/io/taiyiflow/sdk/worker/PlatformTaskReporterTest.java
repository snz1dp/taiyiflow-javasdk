package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.taiyiflow.sdk.collaboration.CollaborationPlugin;
import io.taiyiflow.sdk.worker.auth.WorkerAuth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformTaskReporterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final List<RequestRecord> requests = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode body = MAPPER.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            synchronized (requests) {
                requests.add(new RequestRecord(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        authorization, body));
            }
            int status = "Bearer old-token".equals(authorization) ? 401 : 200;
            byte[] response = (status == 200 ? "{}" : "{\"message\":\"unauthorized\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void refreshesDynamicTokenAndConfirmsTaskAndScheduleReports() throws Exception {
        RefreshingAuth auth = new RefreshingAuth();
        auth.authenticate(baseUrl);
        PlatformTaskReporter reporter = new PlatformTaskReporter(
                baseUrl, auth, Duration.ofSeconds(2), Map.of());
        CollaborationPlugin.TaskDispatch task = new CollaborationPlugin.TaskDispatch();
        task.taskType = "task";
        task.taskId = "task-1";

        reporter.running(task, MAPPER.createObjectNode().put("percent", 10));
        reporter.success(task, Map.of("summary", "done"));

        CollaborationPlugin.TaskDispatch schedule = new CollaborationPlugin.TaskDispatch();
        schedule.taskType = "schedule";
        schedule.runRecordId = "run-record-1";
        reporter.success(schedule, "done");

        synchronized (requests) {
            assertEquals(4, requests.size());
            assertEquals("Bearer old-token", requests.get(0).authorization);
            assertEquals("Bearer new-token", requests.get(1).authorization);
            assertEquals("/api/v1/agents/client/collaborations/task-1", requests.get(0).path);
            assertEquals("running", requests.get(1).body.required("status").asText());
            assertEquals(10, requests.get(1).body.required("status_payload").required("percent").asInt());
            assertEquals("done", requests.get(2).body.required("result_payload").required("summary").asText());
            assertEquals("/api/v1/agents/client/schedules/runs/run-record-1/report", requests.get(3).path);
            assertEquals("done", requests.get(3).body.required("result_payload").required("result").asText());
        }
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

    private record RequestRecord(String method, String path, String authorization, JsonNode body) {
    }
}
