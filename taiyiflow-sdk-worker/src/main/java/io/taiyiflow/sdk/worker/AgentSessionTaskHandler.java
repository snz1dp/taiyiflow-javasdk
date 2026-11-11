package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.collaboration.CollaborationPlugin;
import io.taiyiflow.sdk.core.state.SessionState;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.transport.envelope.StreamEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class AgentSessionTaskHandler implements WorkerTaskHandler, AutoCloseable {
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Function<String, TaiyiAgent> agentFactory;
    private final Map<String, TaiyiAgent> agents = new ConcurrentHashMap<>();
    private final Map<String, SessionExecution> sessions = new ConcurrentHashMap<>();

    public AgentSessionTaskHandler(Function<String, TaiyiAgent> agentFactory) {
        this.agentFactory = agentFactory;
    }

    @Override
    public Object execute(CollaborationPlugin.TaskDispatch task, TaskProgress progress) throws Exception {
        if (task.flowId == null || task.flowId.isBlank()) {
            throw new IllegalArgumentException("Agent session task requires flowId");
        }
        if (task.inputValue == null || task.inputValue.isBlank()) {
            throw new IllegalArgumentException("Agent session task requires inputValue");
        }
        TaiyiAgent agent = agents.computeIfAbsent(task.flowId, agentFactory);
        if (task.sessionId == null || task.sessionId.isBlank()) {
            return new SessionExecution(agent, null).run(task.inputValue);
        }
        String key = task.flowId + "\0" + task.sessionId;
        SessionExecution execution = sessions.computeIfAbsent(
                key, ignored -> new SessionExecution(agent, task.sessionId));
        return execution.run(task.inputValue);
    }

    @Override
    public void close() {
        agents.values().forEach(TaiyiAgent::shutdown);
        agents.clear();
        sessions.clear();
    }

    private static final class SessionExecution {
        private final AgentSession session;
        private final StringBuilder text = new StringBuilder();
        private final AtomicReference<String> protocolError = new AtomicReference<>();
        private final AtomicReference<Throwable> transportError = new AtomicReference<>();

        private SessionExecution(TaiyiAgent agent, String sessionId) {
            this.session = sessionId == null ? agent.newSession() : agent.newSession(sessionId);
            this.session.onStream(new StreamHook() {
                @Override
                public void onChunk(String ignored, String chunk) {
                    text.append(chunk);
                }

                @Override
                public void onEvent(String ignored, StreamEvent event) {
                    if (event.isError()) {
                        protocolError.set(event.getErrorMessage());
                    }
                }

                @Override
                public void onError(String ignored, Throwable error) {
                    transportError.set(error);
                }
            });
        }

        private synchronized ObjectNode run(String input) throws Exception {
            text.setLength(0);
            protocolError.set(null);
            transportError.set(null);
            session.chat(input);
            try {
                session.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            }
            String error = protocolError.get();
            if (error != null) {
                throw new IllegalStateException("Agent session failed: " + error);
            }
            SessionState state = session.getState();
            if (state != SessionState.DONE) {
                throw new IllegalStateException("Agent session ended in state " + state,
                        transportError.get());
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("session_id", session.getSessionId());
            result.put("run_id", session.getRunContext().getRunId());
            result.put("text", text.toString());
            return result;
        }
    }
}
