package io.taiyiflow.sdk.transport.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientToolCallParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesClientToolFunctionAndContext() throws Exception {
        StreamEvent event = event("client_tool", """
                {
                  "flow_id":"flow-1",
                  "context":{"session_id":"session-1","run_id":"run-1","message_id":"message-1"},
                  "server":{"name":"local"},
                  "function":{"id":"call-1","name":"read_file","arguments":{"path":"a.txt"}}
                }
                """);

        ToolCall call = ClientToolCallParser.parse(event, "fallback", "fallback");

        assertEquals("call-1", call.getId());
        assertEquals("read_file", call.getName());
        assertEquals("a.txt", call.getArguments().get("path").asText());
        assertEquals("client_tool", call.getType());
        assertEquals("flow-1", call.getFlowId());
        assertEquals("session-1", call.getSessionId());
        assertEquals("run-1", call.getRunId());
        assertEquals("message-1", call.getMessageId());
    }

    @Test
    void parsesMcpToolAndUsesIdentityFallbacks() throws Exception {
        StreamEvent event = event("mcp_tool", """
                {"context":{"run_id":"run-2"},"function":{"id":"call-2","name":"search","arguments":{}}}
                """);

        ToolCall call = ClientToolCallParser.parse(event, "flow-default", "session-default");

        assertEquals("mcp_tool", call.getType());
        assertEquals("flow-default", call.getFlowId());
        assertEquals("session-default", call.getSessionId());
        assertEquals("run-2", call.getRunId());
    }

    @Test
    void parsesBuiltInCommand() throws Exception {
        StreamEvent event = event("built_in", """
                {
                  "flow_id":"flow-3",
                  "context":{"session_id":"session-3","run_id":"run-3"},
                  "command":{"id":"call-3","name":"shell_exec","arguments":{"command":"pwd"}}
                }
                """);

        ToolCall call = ClientToolCallParser.parse(event, "fallback", "fallback");

        assertEquals("call-3", call.getId());
        assertEquals("shell_exec", call.getName());
        assertEquals("pwd", call.getArguments().get("command").asText());
        assertEquals("built_in", call.getType());
    }

    @Test
    void rejectsPayloadWithoutFunctionOrCommand() throws Exception {
        StreamEvent event = event("client_tool", "{\"context\":{}}");
        assertThrows(IllegalArgumentException.class,
                () -> ClientToolCallParser.parse(event, "flow", "session"));
    }

    private StreamEvent event(String type, String data) throws Exception {
        return new StreamEvent(type, mapper.readTree(data));
    }
}
