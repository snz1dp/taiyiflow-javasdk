package io.taiyiflow.sdk.core.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.transport.envelope.ResumableCheckpoint;
import io.taiyiflow.sdk.transport.rest.ToolCallRecordClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformReconciliationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void deadLetterClientResultResolvedOnlyWhenPlatformProvesReceipt() throws Exception {
        InMemoryReportOutbox outbox = new InMemoryReportOutbox();
        ReportOutbox.Entry entry = outbox.put(ReportOutbox.client(payload("call-1")));
        outbox.markDeadLetter(entry.getId(), "http-422");
        ClientToolResultAdapter adapter = new ClientToolResultAdapter(
                new io.taiyiflow.sdk.transport.rest.RestClient("http://localhost", "key", Duration.ofSeconds(1)),
                outbox, ignored -> { }, 0, Duration.ZERO);

        // Unknown platform state (still waiting) keeps the dead-letter; nothing is replayed.
        assertEquals(0, adapter.reconcileDeadLetters(records(Map.of("call-1", record("waiting_result")))));
        assertEquals(ReportOutbox.Status.DEAD_LETTER, outbox.find(entry.getId()).orElseThrow().getStatus());

        // Missing record is unknown as well.
        assertEquals(0, adapter.reconcileDeadLetters(records(Map.of())));
        assertTrue(outbox.find(entry.getId()).isPresent());

        // Query failure must not resolve the entry.
        assertEquals(0, adapter.reconcileDeadLetters(failingRecords()));
        assertTrue(outbox.find(entry.getId()).isPresent());

        // Terminal success proves receipt: entry is deleted, not re-posted.
        assertEquals(1, adapter.reconcileDeadLetters(records(Map.of("call-1", record("success")))));
        assertTrue(outbox.listAll().isEmpty());
    }

    @Test
    void pauseFrozenDeadLetterIsDiscardedWithoutReplay() throws Exception {
        InMemoryReportOutbox outbox = new InMemoryReportOutbox();
        ReportOutbox.Entry entry = outbox.put(ReportOutbox.client(payload("call-1")));
        outbox.markDeadLetter(entry.getId(), "http-400");
        ClientToolResultAdapter adapter = new ClientToolResultAdapter(
                new io.taiyiflow.sdk.transport.rest.RestClient("http://localhost", "key", Duration.ofSeconds(1)),
                outbox, ignored -> { }, 0, Duration.ZERO);

        assertEquals(1, adapter.reconcileDeadLetters(records(Map.of("call-1", record("client_paused")))));
        assertTrue(outbox.listAll().isEmpty());
    }

    @Test
    void checkpointReconcileAcceptsReceivedCallsNoLongerWaiting() throws Exception {
        InMemoryReportOutbox outbox = new InMemoryReportOutbox();
        CheckpointToolResultsAdapter adapter = new CheckpointToolResultsAdapter(outbox);
        ResumableCheckpoint checkpoint = checkpoint();
        CheckpointToolResultsAdapter.Prepared prepared = adapter.prepare(
                checkpoint, "flow-1", "session-1", Map.of("call-1", checkpointResult("call-1")));
        checkpoint.setWaitingCallIds(List.of());

        // Without platform proof the local conflict classification stays unchanged.
        assertThrows(ReportOutbox.ReportConflictException.class, () -> adapter.reconcile(
                prepared.entry(), checkpoint, "flow-1", "session-1"));
        assertEquals(ReportOutbox.Status.DEAD_LETTER, outbox.find(prepared.entry().getId()).orElseThrow().getStatus());

        // Platform receipt closes the submission without opening a resume stream.
        CheckpointToolResultsAdapter.Prepared resolved = adapter.reconcile(
                prepared.entry(), checkpoint, "flow-1", "session-1",
                records(Map.of("call-1", record("success"))));
        assertFalse(resolved.hasResults());
        assertTrue(outbox.listAll().isEmpty());
    }

    @Test
    void checkpointReconcileKeepsUnknownCallsDeadLettered() throws Exception {
        InMemoryReportOutbox outbox = new InMemoryReportOutbox();
        CheckpointToolResultsAdapter adapter = new CheckpointToolResultsAdapter(outbox);
        ResumableCheckpoint checkpoint = checkpoint();
        CheckpointToolResultsAdapter.Prepared prepared = adapter.prepare(
                checkpoint, "flow-1", "session-1", Map.of("call-1", checkpointResult("call-1")));
        checkpoint.setWaitingCallIds(List.of());

        assertThrows(ReportOutbox.ReportConflictException.class, () -> adapter.reconcile(
                prepared.entry(), checkpoint, "flow-1", "session-1",
                records(Map.of("call-1", record("uncertain")))));
        assertEquals(ReportOutbox.Status.DEAD_LETTER, outbox.find(prepared.entry().getId()).orElseThrow().getStatus());
    }

    @Test
    void checkpointReconcileStillSubmitsRemainingOutstandingCalls() throws Exception {
        InMemoryReportOutbox outbox = new InMemoryReportOutbox();
        CheckpointToolResultsAdapter adapter = new CheckpointToolResultsAdapter(outbox);
        ResumableCheckpoint checkpoint = checkpoint();
        checkpoint.setWaitingCallIds(List.of("call-1", "call-2"));
        Map<String, ObjectNode> pending = new LinkedHashMap<>();
        pending.put("call-1", checkpointResult("call-1"));
        pending.put("call-2", checkpointResult("call-2"));
        CheckpointToolResultsAdapter.Prepared prepared = adapter.prepare(
                checkpoint, "flow-1", "session-1", pending);
        checkpoint.setWaitingCallIds(List.of("call-2"));

        CheckpointToolResultsAdapter.Prepared resumed = adapter.reconcile(
                prepared.entry(), checkpoint, "flow-1", "session-1",
                records(Map.of("call-1", record("success"))));

        assertTrue(resumed.hasResults());
        assertEquals(prepared.entry().getSubmissionId(), resumed.entry().getSubmissionId());
        assertTrue(outbox.find(prepared.entry().getId()).isPresent());
        // The already-received call-1 must not be re-sent; only the outstanding call-2 is replayed.
        assertEquals(List.of("call-2"), replayedCallIds(resumed));
    }

    @Test
    void checkpointReconcileMixedPausedReplayDropsPauseFrozenCall() throws Exception {
        InMemoryReportOutbox outbox = new InMemoryReportOutbox();
        CheckpointToolResultsAdapter adapter = new CheckpointToolResultsAdapter(outbox);
        ResumableCheckpoint checkpoint = checkpoint();
        checkpoint.setWaitingCallIds(List.of("call-1", "call-2"));
        Map<String, ObjectNode> pending = new LinkedHashMap<>();
        pending.put("call-1", checkpointResult("call-1"));
        pending.put("call-2", checkpointResult("call-2"));
        CheckpointToolResultsAdapter.Prepared prepared = adapter.prepare(
                checkpoint, "flow-1", "session-1", pending);
        // call-1 is pause-frozen on the platform; call-2 is still waiting.
        checkpoint.setWaitingCallIds(List.of("call-2"));

        CheckpointToolResultsAdapter.Prepared resumed = adapter.reconcile(
                prepared.entry(), checkpoint, "flow-1", "session-1",
                records(Map.of("call-1", record("client_paused"))));

        assertTrue(resumed.hasResults());
        assertTrue(outbox.find(prepared.entry().getId()).isPresent());
        // Re-submitting a pause-frozen result would violate discard-paused semantics: drop call-1.
        assertEquals(List.of("call-2"), replayedCallIds(resumed));
    }

    private static List<String> replayedCallIds(CheckpointToolResultsAdapter.Prepared prepared) {
        List<String> callIds = new java.util.ArrayList<>();
        prepared.toolResults().forEach(node -> callIds.add(node.path("call_id").asText()));
        return callIds;
    }

    @Test
    void flushResumeReportsReconcilesDeadLetterCheckpointAgainstPlatform() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> platformStatus =
                new java.util.concurrent.atomic.AtomicReference<>("waiting_result");
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agents/flows/flow-1/sessions/session-1/messages/message-1/tool-calls", exchange -> {
            ObjectNode page = MAPPER.createObjectNode().put("total", 1).put("offset", 0);
            page.set("data", MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                    .put("id", "record-1").put("call_id", "call-1")
                    .put("tool_type", "client_tool").put("tool_id", "tool")
                    .put("status", platformStatus.get())));
            byte[] body = MAPPER.writeValueAsBytes(page);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        io.taiyiflow.sdk.TaiyiAgent agent = io.taiyiflow.sdk.TaiyiAgent.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("key").flowId("flow-1").build();
        try {
            ReportOutbox outbox = agent.getToolRuntime().getReportOutbox();
            com.fasterxml.jackson.databind.node.ArrayNode results = MAPPER.createArrayNode().add(payload("call-1"));
            ReportOutbox.Entry entry = outbox.put(ReportOutbox.checkpoint(
                    "flow-1", "session-1", "run-1", "checkpoint-1", 4, "submission-1", results));
            outbox.markDeadLetter(entry.getId(), "http-422");

            // Unknown platform state keeps the dead-letter for manual reconciliation.
            agent.flushResumeReports();
            assertEquals(ReportOutbox.Status.DEAD_LETTER, outbox.find(entry.getId()).orElseThrow().getStatus());

            platformStatus.set("success");
            agent.flushResumeReports();
            assertTrue(outbox.listAll().isEmpty());
        } finally {
            agent.shutdown();
            server.stop(0);
        }
    }

    private static ObjectNode payload(String callId) {
        return MAPPER.createObjectNode()
                .put("flow_id", "flow-1")
                .put("session_id", "session-1")
                .put("message_id", "message-1")
                .put("run_id", "run-1")
                .put("call_id", callId)
                .put("success", true)
                .put("result", "done");
    }

    private static ObjectNode checkpointResult(String callId) {
        return payload(callId);
    }

    private static ResumableCheckpoint checkpoint() {
        ResumableCheckpoint checkpoint = new ResumableCheckpoint();
        checkpoint.setId("checkpoint-1");
        checkpoint.setRunId("run-1");
        checkpoint.setVersion(4);
        checkpoint.setStatus("paused");
        checkpoint.setWaitingCallIds(List.of("call-1"));
        return checkpoint;
    }

    private static ToolCallRecordClient.Record record(String status) {
        ObjectNode node = MAPPER.createObjectNode()
                .put("id", "record-1")
                .put("call_id", "call-1")
                .put("tool_type", "client_tool")
                .put("tool_id", "tool-1")
                .put("run_id", "run-1")
                .put("via_client", true)
                .put("status", status);
        return ToolCallRecordClient.Record.from(node);
    }

    private static ToolCallRecordClient records(Map<String, ToolCallRecordClient.Record> byCallId) {
        return new ToolCallRecordClient(null) {
            @Override
            public Map<String, Record> byCallId(String flowId, String sessionId, String messageId,
                                                String runId, Boolean viaClient) {
                assertEquals("flow-1", flowId);
                assertEquals("session-1", sessionId);
                assertEquals("message-1", messageId);
                assertEquals("run-1", runId);
                assertEquals(Boolean.TRUE, viaClient);
                return byCallId;
            }
        };
    }

    private static ToolCallRecordClient failingRecords() {
        return new ToolCallRecordClient(null) {
            @Override
            public Map<String, Record> byCallId(String flowId, String sessionId, String messageId,
                                                String runId, Boolean viaClient) throws Exception {
                throw new java.io.IOException("platform unreachable");
            }
        };
    }
}