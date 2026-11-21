package io.taiyiflow.sdk.core.session;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ResumeLeaseRegistryTest {

    @Test
    void acquisitionIsExclusivePerKey() {
        ResumeLeaseRegistry registry = new ResumeLeaseRegistry();
        assertTrue(registry.tryAcquire("session-1/checkpoint-1", "owner-a"));
        assertFalse(registry.tryAcquire("session-1/checkpoint-1", "owner-b"));
        assertTrue(registry.tryAcquire("session-1/checkpoint-2", "owner-b"));
        assertTrue(registry.tryAcquire("session-2/checkpoint-1", "owner-b"));
    }

    @Test
    void sameOwnerMayReacquire() {
        ResumeLeaseRegistry registry = new ResumeLeaseRegistry();
        assertTrue(registry.tryAcquire("key", "owner-a"));
        assertTrue(registry.tryAcquire("key", "owner-a"));
        assertFalse(registry.tryAcquire("key", "owner-b"));
    }

    @Test
    void releaseOnlyAppliesToMatchingOwner() {
        ResumeLeaseRegistry registry = new ResumeLeaseRegistry();
        assertTrue(registry.tryAcquire("key", "owner-a"));
        registry.release("key", "owner-b");
        assertFalse(registry.tryAcquire("key", "owner-b"));
        registry.release("key", "owner-a");
        assertTrue(registry.tryAcquire("key", "owner-b"));
    }

    @Test
    void expiredLeaseCanBeTakenOver() throws Exception {
        ResumeLeaseRegistry registry = new ResumeLeaseRegistry();
        assertTrue(registry.tryAcquire("key", "owner-a", Duration.ofMillis(20)));
        assertFalse(registry.tryAcquire("key", "owner-b"));
        Thread.sleep(40);
        assertTrue(registry.tryAcquire("key", "owner-b"));
        assertFalse(registry.tryAcquire("key", "owner-a"));
    }

    @Test
    void defaultLeaseIsFiveMinutes() {
        assertEquals(Duration.ofMinutes(5), ResumeLeaseRegistry.DEFAULT_LEASE);
    }

    @Test
    void releaseOfUnknownKeyIsHarmless() {
        ResumeLeaseRegistry registry = new ResumeLeaseRegistry();
        registry.release("missing", "owner-a");
        assertTrue(registry.tryAcquire("missing", "owner-a"));
    }
}