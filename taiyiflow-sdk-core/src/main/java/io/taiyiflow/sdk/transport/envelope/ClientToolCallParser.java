package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.databind.JsonNode;

public final class ClientToolCallParser {

    private ClientToolCallParser() {
    }

    public static ToolCall parse(StreamEvent event, String defaultFlowId, String defaultSessionId) {
        JsonNode data = event.getData();
        if (data == null || !data.isObject()) {
            throw new IllegalArgumentException("Tool event data must be an object");
        }
        JsonNode function = data.get("function");
        if (function == null || !function.isObject()) {
            function = data.get("command");
        }
        if (function == null || !function.isObject()) {
            throw new IllegalArgumentException("Tool event is missing function or command");
        }

        ToolCall call = new ToolCall();
        call.setId(text(function.get("id"), ""));
        String defaultName = event.isBuiltIn() ? "shell_exec" : "unknown";
        call.setName(text(function.get("name"), defaultName));
        call.setArguments(function.get("arguments"));
        call.setType(event.getEvent());
        call.setFlowId(text(data.get("flow_id"), defaultFlowId));

        JsonNode context = data.get("context");
        call.setSessionId(contextText(context, "session_id", defaultSessionId));
        call.setRunId(contextText(context, "run_id", null));
        call.setMessageId(contextText(context, "message_id", null));
        return call;
    }

    private static String contextText(JsonNode context, String field, String defaultValue) {
        return context != null && context.isObject() ? text(context.get(field), defaultValue) : defaultValue;
    }

    private static String text(JsonNode value, String defaultValue) {
        return value != null && !value.isNull() ? value.asText() : defaultValue;
    }
}
