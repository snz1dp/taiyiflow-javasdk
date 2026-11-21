package io.taiyiflow.sdk.core.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.transport.envelope.ResumableCheckpoint;
import io.taiyiflow.sdk.transport.rest.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportOutboxTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void fileStoreRecoversInterruptedReportsAndQuarantinesCorruption(@TempDir Path state) throws Exception {
        FileReportOutbox first = new FileReportOutbox(state);
        ReportOutbox.Entry entry = first.put(ReportOutbox.client(payload("done")));
        first.markReporting(entry.getId());
        Files.writeString(first.directory().resolve("broken.json"), "not-json");

        FileReportOutbox recovered = new FileReportOutbox(state);

        ReportOutbox.Entry restored = recovered.find(entry.getId()).orElseThrow();
        assertEquals(ReportOutbox.Status.PENDING, restored.getStatus());
        assertEquals("recovered-interrupted-report", restored.getLastError());
        assertEquals(1, recovered.listAll().size());
        try (var files = Files.list(recovered.quarantineDirectory())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void identicalReplayIsDeduplicatedAndDifferentResultBecomesDeadLetter() throws Exception {
        InMemoryReportOutbox outbox = new InMemoryReportOutbox();
        ReportOutbox.Entry original = outbox.put(ReportOutbox.client(payload("done")));
        ObjectNode reordered = MAPPER.createObjectNode();
        reordered.put("call_id", "call-1");
        reordered.put("run_id", "run-1");
        reordered.put("result", "done");
        reordered.put("success", true);
        reordered.put("message_id", "message-1");
        reordered.put("session_id", "session-1");
        reordered.put("flow_id", "flow-1");

        assertEquals(original.getId(), outbox.put(ReportOutbox.client(reordered)).getId());
        assertThrows(ReportOutbox.ReportConflictException.class,
                () -> outbox.put(ReportOutbox.client(payload("other"))));
        assertEquals(ReportOutbox.Status.DEAD_LETTER, outbox.find(original.getId()).orElseThrow().getStatus());
    }

    @Test
    void clientCallbackUsesProtocolSpecificTerminalClassification() {
        StubRestClient paused = new StubRestClient();
        paused.failure = new RestClient.RestException(400, "{\"detail\":\"tool_call_paused\"}");
        InMemoryReportOutbox pausedOutbox = new InMemoryReportOutbox();
        ClientToolResultAdapter pausedAdapter = new ClientToolResultAdapter(
                paused, pausedOutbox, ignored -> { }, 0, Duration.ZERO);

        assertEquals(ClientToolResultAdapter.Outcome.REPORTED,
                pausedAdapter.persistAndReport(payload("done")).outcome());
        assertTrue(pausedOutbox.listAll().isEmpty());

        StubRestClient invalid = new StubRestClient();
        invalid.failure = new RestClient.RestException(422, "{\"detail\":\"identity_mismatch\"}");
        InMemoryReportOutbox invalidOutbox = new InMemoryReportOutbox();
        ClientToolResultAdapter invalidAdapter = new ClientToolResultAdapter(
                invalid, invalidOutbox, ignored -> { }, 0, Duration.ZERO);

        assertEquals(ClientToolResultAdapter.Outcome.DEAD_LETTER,
                invalidAdapter.persistAndReport(payload("done")).outcome());
        assertEquals(ReportOutbox.Status.DEAD_LETTER, invalidOutbox.listAll().get(0).getStatus());
    }

    @Test
    void networkFailureKeepsCanonicalResultPending() {
        StubRestClient rest = new StubRestClient();
        rest.failure = new IOException("offline");
        InMemoryReportOutbox outbox = new InMemoryReportOutbox();
        ClientToolResultAdapter adapter = new ClientToolResultAdapter(
                rest, outbox, ignored -> { }, 0, Duration.ZERO);

        ClientToolResultAdapter.Result result = adapter.persistAndReport(payload("done"));

        assertEquals(ClientToolResultAdapter.Outcome.PENDING, result.outcome());
        assertEquals(ReportOutbox.Status.PENDING, result.entry().getStatus());
        assertEquals("network-error", result.entry().getLastError());
        assertEquals(1, rest.posts.get());
    }

    @Test
    void persistenceFailureMakesOnlyOneDirectAttemptAndRaisesSevereAlert(@TempDir Path state) throws Exception {
        FileReportOutbox outbox = new FileReportOutbox(state,
                new ReportOutbox.Limits(1, 128, 128, 1));
        StubRestClient rest = new StubRestClient();
        rest.failure = new IOException("offline");
        List<ReportAlert> alerts = new ArrayList<>();
        ClientToolResultAdapter adapter = new ClientToolResultAdapter(
                rest, outbox, alerts::add, 3, Duration.ZERO);

        ClientToolResultAdapter.Result result = adapter.persistAndReport(payload("done"));

        assertEquals(ClientToolResultAdapter.Outcome.PENDING, result.outcome());
        assertEquals(1, rest.posts.get());
        assertEquals(1, alerts.size());
        assertEquals("call-1", alerts.get(0).callId());
        assertFalse(alerts.get(0).toString().contains("done"));
    }

    @Test
    void checkpointRetryKeepsSubmissionIdentityAndAcknowledgedReplayIsCleaned() throws Exception {
        InMemoryReportOutbox outbox = new InMemoryReportOutbox();
        CheckpointToolResultsAdapter adapter = new CheckpointToolResultsAdapter(outbox);
        ResumableCheckpoint checkpoint = checkpoint();
        ObjectNode pending = MAPPER.createObjectNode()
                .put("call_id", "call-1")
                .put("result", "done");

        CheckpointToolResultsAdapter.Prepared first = adapter.prepare(
                checkpoint, "flow-1", "session-1", Map.of("call-1", pending));
        CheckpointToolResultsAdapter.Prepared retry = adapter.prepare(
                checkpoint, "flow-1", "session-1", Map.of());

        assertEquals(first.entry().getSubmissionId(), retry.entry().getSubmissionId());
        assertEquals(first.entry().getSubmissionHash(), retry.entry().getSubmissionHash());
        assertEquals(first.entry().getPayload(), retry.entry().getPayload());

        checkpoint.setLastSubmissionId(first.entry().getSubmissionId());
        checkpoint.setLastSubmissionHash(first.entry().getSubmissionHash());
        CheckpointToolResultsAdapter.Prepared acknowledged = adapter.prepare(
                checkpoint, "flow-1", "session-1", Map.of());

        assertFalse(acknowledged.hasResults());
        assertTrue(outbox.listAll().isEmpty());
    }

    @Test
    void checkpointSubmissionFailureClassifiesDeterministicRejectsAndTransientErrors() throws Exception {
        InMemoryReportOutbox outbox = new InMemoryReportOutbox();
        CheckpointToolResultsAdapter adapter = new CheckpointToolResultsAdapter(outbox);
        CheckpointToolResultsAdapter.Prepared prepared = adapter.prepare(
                checkpoint(), "flow-1", "session-1", Map.of("call-1",
                        MAPPER.createObjectNode().put("call_id", "call-1").put("result", "done")));

        assertEquals(ReportOutbox.Status.PENDING,
                adapter.failed(prepared.entry(), new RestClient.RestException(429, "{\"detail\":\"rate_limited\"}")));
        assertEquals(ReportOutbox.Status.PENDING,
                adapter.failed(prepared.entry(), new RestClient.RestException(503, "unavailable")));
        assertEquals(ReportOutbox.Status.PENDING,
                adapter.failed(prepared.entry(), new IOException("offline")));
        assertEquals("network-error", outbox.find(prepared.entry().getId()).orElseThrow().getLastError());

        assertEquals(ReportOutbox.Status.DEAD_LETTER,
                adapter.failed(prepared.entry(),
                        new RestClient.RestException(400, "{\"detail\":\"tool_call_paused\"}")));
        assertEquals("tool_call_paused", outbox.find(prepared.entry().getId()).orElseThrow().getLastError());
        assertEquals(ReportOutbox.Status.DEAD_LETTER,
                adapter.failed(prepared.entry(), new RestClient.RestException(422, "{\"detail\":\"identity_mismatch\"}")));
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

    private static ObjectNode payload(String result) {
        return MAPPER.createObjectNode()
                .put("flow_id", "flow-1")
                .put("session_id", "session-1")
                .put("message_id", "message-1")
                .put("run_id", "run-1")
                .put("call_id", "call-1")
                .put("success", true)
                .put("result", result);
    }

    private static final class StubRestClient extends RestClient {
        private final AtomicInteger posts = new AtomicInteger();
        private Exception failure;

        private StubRestClient() {
            super("http://localhost", "key", Duration.ofSeconds(1));
        }

        @Override
        public JsonNode post(String path, JsonNode body) throws Exception {
            assertEquals(ClientToolResultAdapter.RESULT_PATH, path);
            posts.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return MAPPER.createObjectNode();
        }
    }
}
