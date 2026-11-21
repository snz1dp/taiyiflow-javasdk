package io.taiyiflow.sdk.core.session;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process resume lease registry, the single-instance equivalent of Electron's
 * agent-loop-resume-lock (Web Locks / localStorage lease): one owner per checkpoint key,
 * atomic acquisition, owner-matched release, and takeover of expired leases. The SDK runs a
 * single container per workerId, so an in-process registry covers all local resume paths
 * (AUTO recovery and platform-driven checkpoint resume).
 */
public final class ResumeLeaseRegistry {

    public static final Duration DEFAULT_LEASE = Duration.ofMinutes(5);

    private record Lease(String owner, long expiresAtMillis) {
    }

    private final Map<String, Lease> leases = new ConcurrentHashMap<>();

    public boolean tryAcquire(String key, String owner) {
        return tryAcquire(key, owner, DEFAULT_LEASE);
    }

    public boolean tryAcquire(String key, String owner, Duration ttl) {
        long now = System.currentTimeMillis();
        Lease candidate = new Lease(owner, now + Math.max(1, ttl.toMillis()));
        Lease effective = leases.compute(key, (ignored, existing) ->
                existing == null || existing.expiresAtMillis() <= now || existing.owner().equals(owner)
                        ? candidate : existing);
        return effective.owner().equals(owner);
    }

    public void release(String key, String owner) {
        leases.computeIfPresent(key, (ignored, existing) -> existing.owner().equals(owner) ? null : existing);
    }
}