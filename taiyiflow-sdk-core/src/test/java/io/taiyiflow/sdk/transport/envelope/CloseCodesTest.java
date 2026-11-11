package io.taiyiflow.sdk.transport.envelope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CloseCodesTest {

    @Test
    void normalCode() {
        assertEquals(1000, CloseCodes.NORMAL);
        assertFalse(CloseCodes.isError(CloseCodes.NORMAL));
        assertEquals("Normal closure", CloseCodes.describe(CloseCodes.NORMAL));
    }

    @Test
    void errorCodes() {
        assertTrue(CloseCodes.isError(CloseCodes.INVALID_PATH));
        assertTrue(CloseCodes.isError(CloseCodes.AUTH_FAILED));
        assertTrue(CloseCodes.isError(CloseCodes.INVALID_FIRST_FRAME));
        assertTrue(CloseCodes.isError(CloseCodes.PERMISSION_DENIED));
        assertTrue(CloseCodes.isError(CloseCodes.FLOW_UNAVAILABLE));
        assertTrue(CloseCodes.isError(CloseCodes.SESSION_CONFLICT));
    }

    @Test
    void describeAllCodes() {
        assertEquals("Invalid WebSocket endpoint path", CloseCodes.describe(4000));
        assertEquals("Authentication failed", CloseCodes.describe(4001));
        assertEquals("First frame timeout or invalid request", CloseCodes.describe(4002));
        assertEquals("Permission, quota, or model key denied", CloseCodes.describe(4003));
        assertEquals("Flow not found or unavailable", CloseCodes.describe(4004));
        assertEquals("Session execution conflict", CloseCodes.describe(4009));
    }

    @Test
    void describeUnknownCode() {
        assertEquals("Unknown close code: 9999", CloseCodes.describe(9999));
    }
}
