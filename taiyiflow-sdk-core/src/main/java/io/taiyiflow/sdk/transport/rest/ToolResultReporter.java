package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.dispatch.ToolRuntime;
import io.taiyiflow.sdk.core.report.ClientToolResultAdapter;
import io.taiyiflow.sdk.core.report.InMemoryReportOutbox;
import io.taiyiflow.sdk.core.report.ReportAlert;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public final class ToolResultReporter {
    private static final Logger log = LoggerFactory.getLogger(ToolResultReporter.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ClientToolResultAdapter adapter;

    /** Legacy standalone reporter with an explicitly nonpersistent process-local outbox. */
    public ToolResultReporter(RestClient restClient) {
        this(restClient, 3, Duration.ofMillis(500));
    }

    public ToolResultReporter(RestClient restClient, int maxRetries, Duration initialRetryDelay) {
        this(restClient, new InMemoryReportOutbox(), ToolResultReporter::logSevereAlert,
                maxRetries, initialRetryDelay);
    }

    /** Session/worker reporter backed by the shared ToolRuntime outbox. */
    public ToolResultReporter(RestClient restClient, ToolRuntime runtime) {
        this(restClient, runtime.getReportOutbox(), runtime::reportFailure, 3, Duration.ofMillis(500));
    }

    public ToolResultReporter(RestClient restClient, ToolRuntime runtime,
                              int maxRetries, Duration initialRetryDelay) {
        this(restClient, runtime.getReportOutbox(), runtime::reportFailure, maxRetries, initialRetryDelay);
    }

    private ToolResultReporter(RestClient restClient, io.taiyiflow.sdk.core.report.ReportOutbox outbox,
                               java.util.function.Consumer<ReportAlert> alertHandler,
                               int maxRetries, Duration initialRetryDelay) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        if (initialRetryDelay == null || initialRetryDelay.isNegative()) {
            throw new IllegalArgumentException("initialRetryDelay must be non-negative");
        }
        this.adapter = new ClientToolResultAdapter(restClient, outbox, alertHandler,
                maxRetries, initialRetryDelay);
    }

    /** Compatibility API: failures still surface after the per-call retry budget. */
    public void report(ToolCall call, ToolResult result) {
        reportPayload(buildPayload(call, result));
    }

    public void reportPayload(ObjectNode payload) {
        ClientToolResultAdapter.Result outcome = reportPersisted(payload);
        if (outcome.outcome() != ClientToolResultAdapter.Outcome.REPORTED) {
            throw new ToolResultReportException(payload.path("call_id").asText(), outcome.error());
        }
    }

    /** Session API that distinguishes retained compensation from terminal delivery/dead-letter. */
    public ClientToolResultAdapter.Result reportPersisted(ObjectNode payload) {
        return adapter.persistAndReport(payload);
    }

    public ClientToolResultAdapter.Result reportPersisted(ToolCall call, ToolResult result) {
        return reportPersisted(buildPayload(call, result));
    }

    public void flush() {
        adapter.flush();
    }

    public void flushNow() {
        adapter.flushNow();
    }

    public static ObjectNode buildPayload(ToolCall call, ToolResult result) {
        ObjectNode payload = mapper.createObjectNode();
        putText(payload, "flow_id", call.getFlowId());
        putText(payload, "session_id", call.getSessionId());
        putText(payload, "message_id", call.getMessageId());
        putText(payload, "run_id", call.getRunId());
        putText(payload, "call_id", call.getId());
        payload.put("success", result.isSuccess());
        if (result.getResult() == null) {
            payload.putNull("result");
        } else {
            payload.set("result", result.getResult());
        }
        putText(payload, "error_message", result.getError());
        return payload;
    }

    private static void putText(ObjectNode payload, String field, String value) {
        if (value == null) {
            payload.putNull(field);
        } else {
            payload.put(field, value);
        }
    }

    private static void logSevereAlert(ReportAlert alert) {
        log.error("SEVERE tool result report failure: session={}, run={}, call={}",
                alert.sessionId(), alert.runId(), alert.callId());
    }

    public static final class ToolResultReportException extends RuntimeException {
        public ToolResultReportException(String callId, Throwable cause) {
            super("Failed to report tool result for call " + callId, cause);
        }
    }
}
