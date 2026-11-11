package io.taiyiflow.sdk.transport.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;

import java.time.Duration;

public final class ToolResultReporter {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String RESULT_PATH = "/api/v1/agents/client/call/result";

    private final RestClient restClient;
    private final int maxRetries;
    private final Duration initialRetryDelay;

    public ToolResultReporter(RestClient restClient) {
        this(restClient, 3, Duration.ofMillis(500));
    }

    public ToolResultReporter(RestClient restClient, int maxRetries, Duration initialRetryDelay) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        this.restClient = restClient;
        this.maxRetries = maxRetries;
        this.initialRetryDelay = initialRetryDelay;
    }

    public void report(ToolCall call, ToolResult result) {
        reportPayload(buildPayload(call, result));
    }

    public void reportPayload(ObjectNode payload) {
        String callId = payload.path("call_id").asText();
        for (int attempt = 0; ; attempt++) {
            try {
                restClient.post(RESULT_PATH, payload);
                return;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (attempt >= maxRetries || Thread.currentThread().isInterrupted()) {
                    throw new ToolResultReportException(callId, e);
                }
                sleep(initialRetryDelay.multipliedBy(1L << attempt));
            }
        }
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

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolResultReportException("interrupted", e);
        }
    }

    public static final class ToolResultReportException extends RuntimeException {
        public ToolResultReportException(String callId, Throwable cause) {
            super("Failed to report tool result for call " + callId, cause);
        }
    }
}
