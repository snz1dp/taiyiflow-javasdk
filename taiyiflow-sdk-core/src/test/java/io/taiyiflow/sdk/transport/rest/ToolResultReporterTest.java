package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultReporterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void postsBackendContractAndRetriesThreeTimes() throws Exception {
        StubRestClient restClient = new StubRestClient();
        restClient.failuresBeforeSuccess = 3;
        ToolResultReporter reporter = new ToolResultReporter(restClient, 3, Duration.ofMillis(1));
        ToolCall call = call();
        ToolResult result = ToolResult.success("call-1", mapper.readTree("{\"content\":\"done\"}"));

        reporter.report(call, result);

        assertEquals(4, restClient.calls);
        assertEquals("/api/v1/agents/client/call/result", restClient.path);
        JsonNode body = restClient.body;
        assertEquals("flow-1", body.get("flow_id").asText());
        assertEquals("session-1", body.get("session_id").asText());
        assertEquals("message-1", body.get("message_id").asText());
        assertEquals("run-1", body.get("run_id").asText());
        assertEquals("call-1", body.get("call_id").asText());
        assertTrue(body.get("success").asBoolean());
        assertEquals("done", body.get("result").get("content").asText());
        assertTrue(body.get("error_message").isNull());
    }

    @Test
    void postsErrorMessageAndThrowsAfterRetryBudget() {
        StubRestClient restClient = new StubRestClient();
        restClient.failuresBeforeSuccess = 4;
        ToolResultReporter reporter = new ToolResultReporter(restClient, 3, Duration.ofMillis(1));

        assertThrows(ToolResultReporter.ToolResultReportException.class,
                () -> reporter.report(call(), ToolResult.error("call-1", "denied")));

        assertEquals(4, restClient.calls);
        assertTrue(restClient.body.get("result").isNull());
        assertEquals("denied", restClient.body.get("error_message").asText());
    }

    private ToolCall call() {
        ToolCall call = new ToolCall("call-1", "tool", mapper.createObjectNode());
        call.setFlowId("flow-1");
        call.setSessionId("session-1");
        call.setRunId("run-1");
        call.setMessageId("message-1");
        return call;
    }

    private static final class StubRestClient extends RestClient {
        private int failuresBeforeSuccess;
        private int calls;
        private String path;
        private JsonNode body;

        private StubRestClient() {
            super("http://localhost", "key", Duration.ofSeconds(1));
        }

        @Override
        public JsonNode post(String path, JsonNode body) throws Exception {
            calls++;
            this.path = path;
            this.body = body.deepCopy();
            if (calls <= failuresBeforeSuccess) {
                throw new IOException("offline");
            }
            return new ObjectMapper().createObjectNode().put("code", 0);
        }
    }
}
