package io.taiyiflow.sdk.transport.envelope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventConstantsTest {

    @Test
    void constantsMatchBackendProtocol() {
        assertEquals("message", EventConstants.MESSAGE);
        assertEquals("heartbeat", EventConstants.HEARTBEAT);
        assertEquals("error", EventConstants.ERROR);
        assertEquals("close", EventConstants.CLOSE);
        assertEquals("custom", EventConstants.CUSTOM);
        assertEquals("bytes", EventConstants.BYTES);
        assertEquals("tool_call", EventConstants.TOOL_CALL);
        assertEquals("file_written", EventConstants.FILE_WRITTEN);
        assertEquals("client_tool", EventConstants.CLIENT_TOOL);
        assertEquals("mcp_tool", EventConstants.MCP_TOOL);
        assertEquals("built_in", EventConstants.BUILT_IN);
    }
}
