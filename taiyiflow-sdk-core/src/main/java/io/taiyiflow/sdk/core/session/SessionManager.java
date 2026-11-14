package io.taiyiflow.sdk.core.session;

import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.state.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages multiple concurrent agent sessions.
 * Uses virtual threads for parallel execution.
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final ExecutorService executor;
    private final int maxSessions;
    private final Map<String, AgentSessionImpl> sessions = new ConcurrentHashMap<>();
    private final Map<String, AgentSessionImpl> openSessions = new ConcurrentHashMap<>();

    public SessionManager(ExecutorService executor, int maxSessions) {
        this.executor = executor;
        this.maxSessions = maxSessions;
    }

    public AgentSession createSession(TaiyiAgent agent) {
        return createSession(agent, UUID.randomUUID().toString());
    }

    public synchronized AgentSession createSession(TaiyiAgent agent, String sessionId) {
        if (sessions.size() >= maxSessions) {
            throw new IllegalStateException("Maximum number of sessions reached: " + maxSessions);
        }
        AgentSessionImpl session = new AgentSessionImpl(agent, sessionId, this);
        AgentSessionImpl existing = sessions.putIfAbsent(sessionId, session);
        if (existing != null) {
            throw new IllegalStateException("Session ID is already registered: " + sessionId);
        }
        registerOpenSession(session);
        log.debug("Created session: {}", sessionId);
        return session;
    }

    public AgentSessionImpl getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    synchronized void registerSession(AgentSessionImpl session) {
        AgentSessionImpl existing = sessions.get(session.getSessionId());
        if (existing != null && existing != session) {
            throw new IllegalStateException("Session ID is already registered: " + session.getSessionId());
        }
        if (existing == null) {
            if (sessions.size() >= maxSessions) {
                throw new IllegalStateException("Maximum number of sessions reached: " + maxSessions);
            }
            sessions.put(session.getSessionId(), session);
        }
        registerOpenSession(session);
    }

    private void registerOpenSession(AgentSessionImpl session) {
        AgentSessionImpl previous = openSessions.put(session.getSessionId(), session);
        if (previous != null && previous != session) {
            previous.notifyLifecycleClose();
        }
    }

    synchronized void updateSessionId(String previousId, String resolvedId, AgentSessionImpl session) {
        AgentSessionImpl existing = sessions.putIfAbsent(resolvedId, session);
        if (existing != null && existing != session) {
            throw new IllegalStateException("Resolved session ID is already registered: " + resolvedId);
        }
        sessions.remove(previousId, session);
        openSessions.remove(previousId, session);
        registerOpenSession(session);
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        AgentSessionImpl removed = openSessions.remove(sessionId);
        if (removed != null) {
            removed.notifyLifecycleClose();
            log.debug("Removed session: {}", sessionId);
        }
    }

    void removeSession(String sessionId, AgentSessionImpl session) {
        sessions.remove(sessionId, session);
        if (openSessions.remove(sessionId, session)) {
            session.notifyLifecycleClose();
            log.debug("Removed session: {}", sessionId);
        }
    }

    void detachSession(String sessionId, AgentSessionImpl session) {
        if (sessions.remove(sessionId, session)) {
            log.debug("Detached session: {}", sessionId);
        }
    }

    public void interruptSession(String sessionId) {
        AgentSessionImpl session = sessions.get(sessionId);
        if (session != null) {
            session.interrupt();
        }
    }

    public void shutdownAll() {
        log.info("Shutting down all sessions: {}", openSessions.size());
        List<AgentSessionImpl> open = List.copyOf(openSessions.values());
        for (AgentSessionImpl session : open) {
            try {
                session.interrupt();
            } catch (Exception e) {
                log.warn("Error interrupting session {}: {}", session.getSessionId(), e.getMessage());
            }
        }
        for (AgentSessionImpl session : open) {
            sessions.remove(session.getSessionId(), session);
            if (openSessions.remove(session.getSessionId(), session)) {
                session.notifyLifecycleClose();
            }
        }
    }

    public int getActiveSessionCount() {
        return (int) sessions.values().stream()
                .filter(s -> s.getState() != SessionState.DONE
                          && s.getState() != SessionState.ERROR
                          && s.getState() != SessionState.CANCELLED
                          && s.getState() != SessionState.INTERRUPTED)
                .count();
    }

    ExecutorService getExecutor() {
        return executor;
    }
}
