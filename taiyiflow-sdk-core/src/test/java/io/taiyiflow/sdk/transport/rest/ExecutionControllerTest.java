package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.transport.envelope.AgentLoopControlResult;
import io.taiyiflow.sdk.transport.envelope.ResumableCheckpoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void pauseUsesControlRequestAndClientIdentity() throws Exception {
        StubRestClient rest = new StubRestClient();
        rest.postResponse = mapper.readTree("""
                {"session_id":"session-1","run_id":"run-1","status":"pause_requested",
                 "request_id":"request-1","checkpoint_id":"checkpoint-1",
                 "subtask_cancel_requested":2,"subtask_cancel_pending":1,"subtask_cancel_failed":false}
                """);
        ExecutionController controller = new ExecutionController(rest, "client-1", "device-1");

        AgentLoopControlResult result = controller.pause("session/1", "run-1");

        assertEquals("POST /api/v1/run/sessions/session%2F1/pause", rest.requests.get(0));
        assertEquals("run-1", rest.lastBody.required("run_id").asText());
        assertEquals("client-1", rest.lastBody.required("client_id").asText());
        assertEquals("device-1", rest.lastBody.required("device_id").asText());
        assertFalse(rest.lastBody.required("request_id").asText().isBlank());
        assertEquals("checkpoint-1", result.getCheckpointId());
        assertEquals(2, result.getSubtaskCancelRequested());
        assertEquals(1, result.getSubtaskCancelPending());
    }

    @Test
    void cancelGeneratesIdempotentRequestIdWithoutClientFields() throws Exception {
        StubRestClient rest = new StubRestClient();
        rest.postResponse = mapper.readTree("""
                {"session_id":"session-1","run_id":"run-1","status":"cancel_requested","request_id":"request-2"}
                """);
        ExecutionController controller = new ExecutionController(rest, "client-1", null);

        AgentLoopControlResult result = controller.cancel("session-1", "run-1");

        assertEquals("POST /api/v1/run/sessions/session-1/cancel", rest.requests.get(0));
        assertFalse(rest.lastBody.has("client_id"));
        assertFalse(rest.lastBody.has("device_id"));
        assertFalse(rest.lastBody.required("request_id").asText().isBlank());
        assertEquals("cancel_requested", result.getStatus());
    }

    @Test
    void resumableQueryUsesClientIdentityAndParsesCheckpoint() throws Exception {
        StubRestClient rest = new StubRestClient();
        rest.getResponse = mapper.readTree("""
                [{"id":"checkpoint-1","flow_id":"flow-1","session_id":"session-1","run_id":"run-1",
                  "origin_client_id":"client-1","origin_device_id":"device-1","status":"paused","version":3,
                  "resume_stage":"dispatch_tools","pause_reason":"user","requires_user_resume":true,
                  "waiting_call_ids":["waiting-1"],"pending_call_ids":["pending-1"],"uncertain_call_ids":[],
                  "updated_at":"2026-11-11T10:00:00Z","expires_at":null}]
                """);
        ExecutionController controller = new ExecutionController(rest, "client-1", "device-1");

        List<ResumableCheckpoint> result = controller.listResumable("flow 1", "session/1");

        assertEquals("GET /api/v1/run/checkpoints/resumable?client_id=client-1&device_id=device-1&flow_id=flow+1&session_id=session%2F1",
                rest.requests.get(0));
        assertEquals(1, result.size());
        ResumableCheckpoint checkpoint = result.get(0);
        assertEquals("checkpoint-1", checkpoint.getId());
        assertEquals(3, checkpoint.getVersion());
        assertEquals("dispatch_tools", checkpoint.getResumeStage());
        assertTrue(checkpoint.isRequiresUserResume());
        assertEquals(List.of("waiting-1"), checkpoint.getWaitingCallIds());
        assertTrue(checkpoint.hasUncertainSideEffects());
        assertEquals("2026-11-11T10:00:00Z", checkpoint.getUpdatedAt());
    }

    private static final class StubRestClient extends RestClient {
        private final List<String> requests = new ArrayList<>();
        private JsonNode lastBody;
        private JsonNode postResponse;
        private JsonNode getResponse;

        private StubRestClient() {
            super("http://localhost", "key", Duration.ofSeconds(1));
        }

        @Override
        public JsonNode post(String path, JsonNode body) {
            requests.add("POST " + path);
            lastBody = body;
            return postResponse;
        }

        @Override
        public JsonNode get(String path) {
            requests.add("GET " + path);
            return getResponse;
        }
    }
}
