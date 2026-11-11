package io.taiyiflow.sdk.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.transport.envelope.AgentLoopControlResult;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import io.taiyiflow.sdk.transport.envelope.ResumableCheckpoint;
import io.taiyiflow.sdk.transport.envelope.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RunContextTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void retainsRequestTemplateAndStreamExecutionIdentity() throws Exception {
        ChatRequest request = new ChatRequest("hello", "session-1");
        request.setTweaks(Map.of("node", mapper.createObjectNode().put("value", 1)));
        request.setInputFiles(List.of("/tmp/input.txt"));
        request.setRequestType("agent");
        RunContext context = new RunContext();

        context.begin(request);
        ((ObjectNode) request.getTweaks().get("node")).put("value", 2);
        context.update(new StreamEvent("message", mapper.readTree("""
                {"chunk":"ok","run_id":"run-1","execution_attempt_id":"attempt-1",
                 "event_id":"event-1","checkpoint_id":"checkpoint-1","checkpoint_version":2}
                """)));

        assertEquals("hello", context.getLastInputValue());
        assertEquals("agent", context.getRequestType());
        assertEquals(List.of("/tmp/input.txt"), context.getLastInputFiles());
        assertEquals(1, context.getLastTweaks().get("node").required("value").asInt());
        assertEquals("run-1", context.getRunId());
        assertEquals("attempt-1", context.getExecutionAttemptId());
        assertEquals("event-1", context.getAfterEventId());
        assertEquals("checkpoint-1", context.getCheckpointId());
        assertEquals(2, context.getCheckpointVersion());
        assertTrue(context.isPersisted());
    }

    @Test
    void ignoresEventsFromAnotherRun() throws Exception {
        RunContext context = new RunContext();
        ChatRequest request = new ChatRequest("hello", "session-1");
        request.setRunId("run-current");
        context.begin(request);

        context.update(new StreamEvent("message", mapper.readTree("""
                {"run_id":"run-old","event_id":"event-old"}
                """)));

        assertEquals("run-current", context.getRunId());
        assertNull(context.getAfterEventId());
    }

    @Test
    void staleControlAndCheckpointResponsesCannotAdvanceContext() {
        RunContext context = new RunContext();
        long generation = context.nextRecoveryGeneration();
        AgentLoopControlResult control = new AgentLoopControlResult();
        control.setRunId("run-control");
        control.setCheckpointId("checkpoint-control");
        ResumableCheckpoint checkpoint = new ResumableCheckpoint();
        checkpoint.setRunId("run-checkpoint");
        checkpoint.setId("checkpoint-1");
        checkpoint.setVersion(4);

        context.nextRecoveryGeneration();

        assertFalse(context.update(control, generation));
        assertFalse(context.update(checkpoint, generation));
        assertNull(context.getRunId());
        assertNull(context.getCheckpointId());
        assertTrue(context.update(control, context.getRecoveryGeneration()));
        assertEquals("run-control", context.getRunId());
        assertTrue(context.update(checkpoint, context.getRecoveryGeneration()));
        assertEquals(4, context.getCheckpointVersion());
        assertTrue(context.isPersisted());
    }
}
