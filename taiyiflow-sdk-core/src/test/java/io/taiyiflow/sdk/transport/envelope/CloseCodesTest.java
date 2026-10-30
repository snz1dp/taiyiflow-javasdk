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
        assertTrue(CloseCodes.isError(CloseCodes.AUTH_FAILED));
        assertTrue(CloseCodes.isError(CloseCodes.FLOW_NOT_FOUND));
        assertTrue(CloseCodes.isError(CloseCodes.INVALID_REQUEST));
        assertTrue(CloseCodes.isError(CloseCodes.RATE_LIMITED));
        assertTrue(CloseCodes.isError(CloseCodes.INTERNAL_ERROR));
    }

    @Test
    void describeAllCodes() {
        assertEquals("Authentication failed", CloseCodes.describe(4001));
        assertEquals("Flow not found", CloseCodes.describe(4002));
        assertEquals("Invalid request", CloseCodes.describe(4003));
        assertEquals("Rate limited", CloseCodes.describe(4004));
        assertEquals("Internal server error", CloseCodes.describe(4009));
    }

    @Test
    void describeUnknownCode() {
        assertEquals("Unknown close code: 9999", CloseCodes.describe(9999));
    }
}
