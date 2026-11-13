package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformTaskReporterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TASK_PATH = "/api/v1/agents/client/collaborations/task-1";
    private static final String RUN_PATH = "/api/v1/agents/client/schedules/runs/run-record-1";

    private HttpServer server;
    private String baseUrl;
    private final List<RequestRecord> requests = new ArrayList<>();
    private ObjectNode taskSnapshot;
    private ObjectNode runSnapshot;
    private boolean conflictNextTaskPatch;

    @BeforeEach
    void startServer() throws Exception {
        taskSnapshot = MAPPER.createObjectNode().put("id", "task-1").put("status", "dispatched").put("version", 3);
        runSnapshot = MAPPER.createObjectNode().put("id", "run-record-1").put("status", "running");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode body = rawBody.isEmpty() ? null : MAPPER.readTree(rawBody);
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            synchronized (requests) {
                requests.add(new RequestRecord(method, path, authorization, body));
            }
            int status = "Bearer old-token".equals(authorization) ? 401 : 200;
            JsonNode response = MAPPER.createObjectNode();
            if (status == 200 && "GET".equals(method) && TASK_PATH.equals(path)) {
                response = taskSnapshot;
            } else if (status == 200 && "PATCH".equals(method) && TASK_PATH.equals(path)) {
                if (conflictNextTaskPatch) {
                    conflictNextTaskPatch = false;
                    taskSnapshot.put("status", "running");
                    taskSnapshot.put("version", taskSnapshot.path("version").asInt() + 1);
                    status = 409;
                } else {
                    apply(taskSnapshot, body);
                    response = taskSnapshot;
                }
            } else if (status == 200 && "GET".equals(method) && RUN_PATH.equals(path)) {
                response = runSnapshot;
            } else if (status == 200 && "POST".equals(method) && (RUN_PATH + "/report").equals(path)) {
                apply(runSnapshot, body);
                response = runSnapshot;
            }
            byte[] bytes = (status == 401 ? "{\"message\":\"unauthorized\"}" : MAPPER.writeValueAsString(response))
                    .getBytes(StandardCharsets.UTF_8);
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
        if (server != null) server.stop(0);
    }

    @Test
    void refreshesDynamicTokenAndConfirmsTaskAndScheduleReports() throws Exception {
        PlatformTaskReporter reporter = reporter();
        CollaborationPlugin.TaskDispatch task = task();
        reporter.running(task, MAPPER.createObjectNode().put("percent", 10));
        reporter.success(task, Map.of("summary", "done"));
        reporter.success(schedule(), "done");
        synchronized (requests) {
            assertEquals(7, requests.size());
            assertEquals("Bearer old-token", requests.get(0).authorization);
            assertEquals("Bearer new-token", requests.get(1).authorization);
            assertEquals("GET", requests.get(0).method);
            assertEquals(TASK_PATH, requests.get(0).path);
            assertEquals("PATCH", requests.get(2).method);
            assertEquals(3, requests.get(2).body.required("expected_version").asInt());
            assertEquals("running", requests.get(2).body.required("status").asText());
            assertEquals(10, requests.get(2).body.required("status_payload").required("percent").asInt());
            assertEquals(4, requests.get(4).body.required("expected_version").asInt());
            assertEquals("done", requests.get(4).body.required("result_payload").required("summary").asText());
            assertEquals("GET", requests.get(5).method);
            assertEquals(RUN_PATH, requests.get(5).path);
            assertEquals(RUN_PATH + "/report", requests.get(6).path);
            assertEquals("done", requests.get(6).body.required("result_payload").required("result").asText());
        }
    }

    @Test
    void identicalTerminalReportIsIdempotentAndDifferentResultDoesNotOverwrite() throws Exception {
        taskSnapshot.put("status", "success").put("version", 8);
        taskSnapshot.putObject("result_payload").put("summary", "done");
        PlatformTaskReporter reporter = reporter();
        reporter.success(task(), Map.of("summary", "done"));
        assertThrows(PlatformTaskReporter.ReportConflictException.class,
                () -> reporter.success(task(), Map.of("summary", "other")));
        synchronized (requests) {
            assertEquals(3, requests.size());
            assertEquals("GET", requests.get(0).method);
            assertEquals("GET", requests.get(1).method);
            assertEquals("GET", requests.get(2).method);
        }
    }

    @Test
    void versionConflictRereadsAndRetriesOnlyLegalTransition() throws Exception {
        conflictNextTaskPatch = true;
        PlatformTaskReporter reporter = reporter();
        reporter.success(task(), "done");
        synchronized (requests) {
            assertEquals(5, requests.size());
            assertEquals(3, requests.get(2).body.required("expected_version").asInt());
            assertEquals(4, requests.get(4).body.required("expected_version").asInt());
            assertEquals("success", requests.get(4).body.required("status").asText());
        }
    }

    @Test
    void cancelledUsesLatestVersionAndRepeatsWithoutPatching() throws Exception {
        taskSnapshot.put("status", "running").put("version", 9);
        PlatformTaskReporter reporter = reporter();
        reporter.cancelled(task(), "cancel-1");
        reporter.cancelled(task(), "cancel-1");
        synchronized (requests) {
            assertEquals(4, requests.size());
            assertEquals("PATCH", requests.get(2).method);
            assertEquals(9, requests.get(2).body.required("expected_version").asInt());
            assertEquals("cancel-1", requests.get(2).body.required("status_payload").required("cancel_request_id").asText());
            assertEquals("GET", requests.get(3).method);
        }
    }

    private PlatformTaskReporter reporter() throws Exception {
        RefreshingAuth auth = new RefreshingAuth();
        auth.authenticate(baseUrl);
        return new PlatformTaskReporter(baseUrl, auth, Duration.ofSeconds(2), Map.of());
    }

    private static CollaborationPlugin.TaskDispatch task() {
        CollaborationPlugin.TaskDispatch task = new CollaborationPlugin.TaskDispatch();
        task.taskType = "task";
        task.taskId = "task-1";
        return task;
    }

    private static CollaborationPlugin.TaskDispatch schedule() {
        CollaborationPlugin.TaskDispatch schedule = new CollaborationPlugin.TaskDispatch();
        schedule.taskType = "schedule";
        schedule.runRecordId = "run-record-1";
        return schedule;
    }

    private static void apply(ObjectNode snapshot, JsonNode report) {
        snapshot.put("status", report.required("status").asText());
        if (report.has("status_payload")) snapshot.set("status_payload", report.get("status_payload"));
        if (report.has("result_payload")) snapshot.set("result_payload", report.get("result_payload"));
        if (report.has("error_message")) snapshot.put("error_message", report.get("error_message").asText());
        if (report.path("status_payload").has("cancel_request_id")) {
            snapshot.put("cancel_request_id", report.path("status_payload").path("cancel_request_id").asText());
        }
        if (snapshot.has("version")) snapshot.put("version", snapshot.path("version").asInt() + 1);
    }

    private static final class RefreshingAuth extends WorkerAuth {
        private volatile String token = "old-token";
        @Override public String accessToken() { return token; }
        @Override protected void doAuthenticate(String baseUrl) { }
        @Override protected boolean doRefresh() { token = "new-token"; return true; }
        @Override protected boolean supportsRefresh() { return true; }
    }

    private record RequestRecord(String method, String path, String authorization, JsonNode body) { }
}
