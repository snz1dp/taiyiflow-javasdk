package io.taiyiflow.sdk.core.session;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.state.SessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    void maxSessionsEnforced() {
        SessionManager limited = new SessionManager(executor, 2);
        limited.createSession(agent, "s1");
        limited.createSession(agent, "s2");
        assertThrows(IllegalStateException.class, () -> limited.createSession(agent, "s3"));
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
    void shutdownAllClearsRegistry() {
        sessionManager.createSession(agent, "s1");
        sessionManager.createSession(agent, "s2");
        sessionManager.shutdownAll();
        assertEquals(0, sessionManager.getActiveSessionCount());
    }
}
