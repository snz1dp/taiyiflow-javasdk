package io.taiyiflow.sdk.core.session;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.state.SessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    private SessionManager sessionManager;
    private ExecutorService executor;
    private TaiyiAgent agent;

    @BeforeEach
    void setUp() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        sessionManager = new SessionManager(executor, 10);
        agent = TaiyiAgent.builder()
                .baseUrl("http://localhost:9999")
                .apiKey("test-key")
                .flowId("test-flow")
                .build();
    }

    @AfterEach
    void tearDown() {
        sessionManager.shutdownAll();
        executor.shutdown();
    }

    @Test
    void createSessionGeneratesUniqueIds() {
        AgentSession s1 = sessionManager.createSession(agent);
        AgentSession s2 = sessionManager.createSession(agent);
        assertNotEquals(s1.getSessionId(), s2.getSessionId());
    }

    @Test
    void createSessionWithCustomId() {
        AgentSession session = sessionManager.createSession(agent, "my-session");
        assertEquals("my-session", session.getSessionId());
    }

    @Test
    void duplicateSessionIdIsRejected() {
        AgentSession session = sessionManager.createSession(agent, "same-id");

        assertThrows(IllegalStateException.class, () -> sessionManager.createSession(agent, "same-id"));
        assertSame(session, sessionManager.getSession("same-id"));
    }

    @Test
    void staleSessionCannotRemoveReplacement() {
        AgentSessionImpl original = (AgentSessionImpl) sessionManager.createSession(agent, "same-id");
        sessionManager.removeSession("same-id");
        AgentSession replacement = sessionManager.createSession(agent, "same-id");

        sessionManager.removeSession("same-id", original);

        assertSame(replacement, sessionManager.getSession("same-id"));
    }

    @Test
    void maxSessionsEnforced() {
        SessionManager limited = new SessionManager(executor, 2);
        limited.createSession(agent, "s1");
        limited.createSession(agent, "s2");
        assertThrows(IllegalStateException.class, () -> limited.createSession(agent, "s3"));
    }

    @Test
    void concurrentCreationCannotExceedMaxSessions() throws Exception {
        SessionManager limited = new SessionManager(executor, 1);
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) {
                String sessionId = "session-" + i;
                futures.add(callers.submit(() -> {
                    start.await();
                    try {
                        limited.createSession(agent, sessionId);
                        created.incrementAndGet();
                    } catch (IllegalStateException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(2, TimeUnit.SECONDS);
            }

            assertEquals(1, created.get());
            assertEquals(23, rejected.get());
            assertEquals(1, limited.getActiveSessionCount());
        } finally {
            start.countDown();
            callers.shutdownNow();
            limited.shutdownAll();
        }
    }

    @Test
    void getSessionReturnsRegistered() {
        sessionManager.createSession(agent, "s1");
        assertNotNull(sessionManager.getSession("s1"));
        assertNull(sessionManager.getSession("nonexistent"));
    }

    @Test
    void removeSessionCleansUp() {
        sessionManager.createSession(agent, "s1");
        sessionManager.removeSession("s1");
        assertNull(sessionManager.getSession("s1"));
    }

    @Test
    void multipleSessionsAreIndependent() {
        AgentSession s1 = sessionManager.createSession(agent, "s1");
        AgentSession s2 = sessionManager.createSession(agent, "s2");
        AgentSession s3 = sessionManager.createSession(agent, "s3");

        assertEquals(3, sessionManager.getActiveSessionCount());
        sessionManager.removeSession("s2");
        assertEquals(2, sessionManager.getActiveSessionCount());
    }

    @Test
    void awaitTimeoutReturnsFalseForPendingSession() throws Exception {
        AgentSession session = sessionManager.createSession(agent, "pending");
        assertFalse(session.await(Duration.ofMillis(10)));
    }

    @Test
    void shutdownAllClearsRegistry() {
        sessionManager.createSession(agent, "s1");
        sessionManager.createSession(agent, "s2");
        sessionManager.shutdownAll();
        assertEquals(0, sessionManager.getActiveSessionCount());
    }
}
