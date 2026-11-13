package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestCapabilityClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sessionClientUsesSessionAndMessageResources() throws Exception {
        StubRestClient rest = new StubRestClient();
        SessionClient client = new SessionClient(rest);

        assertEquals("session-new", client.createSessionId());
        client.list("flow 1", 0, 20);
        client.get("flow 1", "session/1");
        client.rename("flow 1", "session/1", "new name");
        client.messages("flow 1", "session/1");
        client.deleteMessages("flow 1", "session/1", List.of("message-1", "message-2"));
        client.deleteAll("flow 1", List.of("session/1", "session-2"));

        assertEquals("GET /api/v1/session_id", rest.requests.get(0));
        assertEquals("GET /api/v1/agents/flows/flow%201/sessions?limit=20&offset=0", rest.requests.get(1));
        assertEquals("GET /api/v1/agents/flows/flow%201/sessions/session%2F1", rest.requests.get(2));
        assertEquals("POST /api/v1/agents/flows/flow%201/sessions/session%2F1", rest.requests.get(3));
        assertEquals("new name", rest.lastBody.required("session_name").asText());
        assertEquals("GET /api/v1/agents/flows/flow%201/sessions/session%2F1/messages", rest.requests.get(4));
        assertEquals("DELETE /api/v1/agents/flows/flow%201/sessions/session%2F1/messages?message_ids=message-1%2Cmessage-2",
                rest.requests.get(5));
        assertEquals("DELETE /api/v1/agents/flows/flow%201/sessions/session-2", rest.requests.get(7));
    }

    @Test
    void skillClientSupportsVisibilityUploadAndInstallations() throws Exception {
        StubRestClient rest = new StubRestClient();
        SkillClient client = new SkillClient(rest);

        client.list(Map.of("flow_id", "flow-1", "available_tools", List.of("shell", "file")));
        client.upload(mapper.createObjectNode().put("name", "custom-skill"));
        client.reportInstallation(mapper.createObjectNode().put("skill_package_id", "skill-1"));
        client.installations(Map.of("offset", 0, "limit", 10));
        client.uninstall("record-1");
        client.delete("skill-1");

        assertEquals("GET /api/v1/agents/skills?available_tools=shell&available_tools=file&flow_id=flow-1",
                rest.requests.get(0));
        assertEquals("POST /api/v1/agents/skills/upload", rest.requests.get(1));
        assertEquals("POST /api/v1/agents/skills/installations", rest.requests.get(2));
        assertEquals("GET /api/v1/agents/skills/installations?limit=10&offset=0", rest.requests.get(3));
        assertEquals("POST /api/v1/agents/skills/installations/record-1/uninstall", rest.requests.get(4));
        assertEquals("DELETE /api/v1/agents/skills/skill-1", rest.requests.get(5));
    }

    @Test
    void memoryClientKeepsServerSideLifecycle() throws Exception {
        StubRestClient rest = new StubRestClient();
        MemoryClient client = new MemoryClient(rest);

        client.list(Map.of("flow_id", "flow-1", "session_id", "session-1"));
        client.create(mapper.createObjectNode().put("content", "remember"));
        JsonNode createdBody = rest.lastBody;
        client.get("memory-1");
        client.forget("memory-1");
        client.restore("memory-1");

        assertEquals("GET /api/v1/agents/memories?flow_id=flow-1&session_id=session-1", rest.requests.get(0));
        assertEquals("POST /api/v1/agents/memories", rest.requests.get(1));
        assertEquals("remember", createdBody.required("content").asText());
        assertEquals("GET /api/v1/agents/memories/memory-1", rest.requests.get(2));
        assertEquals("POST /api/v1/agents/memories/memory-1/forget", rest.requests.get(3));
        assertEquals("POST /api/v1/agents/memories/memory-1/restore", rest.requests.get(4));
    }

    @Test
    void scheduleClientUsesPatchEnableDisableAndRuns() throws Exception {
        StubRestClient rest = new StubRestClient();
        ScheduleClient client = new ScheduleClient(rest);

        client.list(Map.of("flow_id", "flow-1", "is_template", false));
        client.create(mapper.createObjectNode().put("name", "daily"));
        client.update("schedule-1", mapper.createObjectNode().put("cron", "0 0 * * *"));
        client.enable("schedule-1", Map.of("client_id", "client-1", "device_id", "device-1"));
        client.disable("schedule-1", Map.of());
        client.runs("schedule-1", 3, 10);
        client.delete("schedule-1");

        assertEquals("GET /api/v1/agents/client/schedules?flow_id=flow-1&is_template=false", rest.requests.get(0));
        assertEquals("POST /api/v1/agents/client/schedules", rest.requests.get(1));
        assertEquals("PATCH /api/v1/agents/client/schedules/schedule-1", rest.requests.get(2));
        assertEquals("POST /api/v1/agents/client/schedules/schedule-1/enable?client_id=client-1&device_id=device-1",
                rest.requests.get(3));
        assertEquals("POST /api/v1/agents/client/schedules/schedule-1/disable", rest.requests.get(4));
        assertEquals("GET /api/v1/agents/client/schedules/schedule-1/runs?limit=10&offset=3", rest.requests.get(5));
        assertEquals("DELETE /api/v1/agents/client/schedules/schedule-1", rest.requests.get(6));
    }

    @Test
    void collaborationTaskClientUsesConfirmedRestReports() throws Exception {
        StubRestClient rest = new StubRestClient();
        CollaborationTaskClient client = new CollaborationTaskClient(rest);

        client.running("task/1", mapper.createObjectNode().put("percent", 25), 7L);
        assertEquals("PATCH /api/v1/agents/client/collaborations/task%2F1", rest.requests.get(0));
        assertEquals("running", rest.lastBody.required("status").asText());
        assertEquals(25, rest.lastBody.required("status_payload").required("percent").asInt());
        assertEquals(7, rest.lastBody.required("expected_version").asLong());

        client.success("task/1", "done", 8L);
        assertEquals("done", rest.lastBody.required("result_payload").required("result").asText());
        assertEquals(8, rest.lastBody.required("expected_version").asLong());
        client.failure("task/1", "boom", 9L);
        assertEquals("boom", rest.lastBody.required("error_message").asText());
        assertEquals(9, rest.lastBody.required("expected_version").asLong());
        client.get("task/1");
        client.list(Map.of("active_only", true));
        client.cancelled("task/1", "cancel-1", 10L);

        assertEquals("GET /api/v1/agents/client/collaborations/task%2F1", rest.requests.get(3));
        assertEquals("GET /api/v1/agents/client/collaborations?active_only=true", rest.requests.get(4));
        assertEquals("PATCH /api/v1/agents/client/collaborations/task%2F1", rest.requests.get(5));
        assertEquals("cancelled", rest.lastBody.required("status").asText());
        assertEquals("cancel-1", rest.lastBody.required("status_payload").required("cancel_request_id").asText());
        assertEquals(10, rest.lastBody.required("expected_version").asLong());
    }

    @Test
    void scheduleClientReportsRunStatus() throws Exception {
        StubRestClient rest = new StubRestClient();
        ScheduleClient client = new ScheduleClient(rest);

        client.getRun("run/1");
        client.report("run/1", mapper.createObjectNode().put("status", "success"));

        assertEquals("GET /api/v1/agents/client/schedules/runs/run%2F1", rest.requests.get(0));
        assertEquals("POST /api/v1/agents/client/schedules/runs/run%2F1/report", rest.requests.get(1));
        assertEquals("success", rest.lastBody.required("status").asText());
    }

    @Test
    void mcpClientSeparatesJsonConfigurationFromFormStatus() throws Exception {
        StubRestClient rest = new StubRestClient();
        McpClient client = new McpClient(rest);

        client.list("flow-1", "desktop");
        client.create(mapper.createObjectNode().put("name", "local"));
        client.update("server-1", mapper.createObjectNode().put("url", "http://localhost/mcp"));
        client.setEnabled("server-1", true);
        client.delete("server-1");

        assertEquals("GET /api/v1/agents/mcp/servers?client_type=desktop&flow_id=flow-1", rest.requests.get(0));
        assertEquals("POST /api/v1/agents/mcp/servers", rest.requests.get(1));
        assertEquals("POST /api/v1/agents/mcp/servers/server-1", rest.requests.get(2));
        assertEquals("FORM /api/v1/agents/mcp/servers/server-1/status enabled=true", rest.requests.get(3));
        assertEquals("DELETE /api/v1/agents/mcp/servers/server-1", rest.requests.get(4));
    }

    @Test
    void configClientReadsRuntimeConfiguration() throws Exception {
        StubRestClient rest = new StubRestClient();
        new ConfigClient(rest).get();
        assertEquals("GET /api/v1/config", rest.requests.get(0));
    }

    private static final class StubRestClient extends RestClient {
        private final List<String> requests = new ArrayList<>();
        private JsonNode lastBody;

        private StubRestClient() {
            super("http://localhost", "key", Duration.ofSeconds(1));
        }

        @Override
        public JsonNode get(String path) {
            requests.add("GET " + path);
            ObjectMapper mapper = new ObjectMapper();
            return path.equals("/api/v1/session_id")
                    ? mapper.createObjectNode().put("session_id", "session-new")
                    : mapper.createObjectNode();
        }

        @Override
        public JsonNode post(String path, JsonNode body) {
            requests.add("POST " + path);
            lastBody = body;
            return new ObjectMapper().createObjectNode();
        }

        @Override
        public JsonNode patch(String path, JsonNode body) {
            requests.add("PATCH " + path);
            lastBody = body;
            return new ObjectMapper().createObjectNode();
        }

        @Override
        public JsonNode postForm(String path, Map<String, String> form) {
            requests.add("FORM " + path + " enabled=" + form.get("enabled"));
            return new ObjectMapper().createObjectNode();
        }

        @Override
        public void delete(String path) {
            requests.add("DELETE " + path);
        }
    }
}
