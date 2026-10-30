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
    }
}
