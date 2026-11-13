package io.taiyiflow.sdk.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.collaboration.CollaborationPlugin;
import io.taiyiflow.sdk.core.report.ReportOutbox;
import io.taiyiflow.sdk.core.state.SessionState;
import io.taiyiflow.sdk.core.subtask.SubtaskCapabilitySnapshot;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import io.taiyiflow.sdk.transport.envelope.StreamEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class AgentSessionTaskHandler implements WorkerTaskHandler, AutoCloseable {
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Function<String, TaiyiAgent> agentFactory;
    private volatile SubtaskMcpResolver mcpResolver = (flowId, names) -> mapper.createObjectNode();
    private final Map<String, TaiyiAgent> agents = new ConcurrentHashMap<>();
    private final Map<String, SessionExecution> sessions = new ConcurrentHashMap<>();
    private volatile BiConsumer<CollaborationPlugin.TaskDispatch, TaskControl> controlRegistered =
            (task, control) -> { };
    private volatile BiConsumer<CollaborationPlugin.TaskDispatch, TaskControl> controlUnregistered =
            (task, control) -> { };

    public AgentSessionTaskHandler(Function<String, TaiyiAgent> agentFactory) {
        this.agentFactory = agentFactory;
    }

    void onControl(BiConsumer<CollaborationPlugin.TaskDispatch, TaskControl> registered,
                   BiConsumer<CollaborationPlugin.TaskDispatch, TaskControl> unregistered) {
        this.controlRegistered = registered;
        this.controlUnregistered = unregistered;
    }

    void subtaskMcpResolver(SubtaskMcpResolver value) {
        this.mcpResolver = value == null ? (flowId, names) -> mapper.createObjectNode() : value;
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
            return new SessionExecution(agent, null).run(task);
        }
        String key = task.flowId + "\0" + task.sessionId;
        SessionExecution execution = sessions.computeIfAbsent(
                key, ignored -> new SessionExecution(agent, task.sessionId));
        return execution.run(task);
    }

    private ChatRequest chatRequest(CollaborationPlugin.TaskDispatch task) throws Exception {
        if (!Set.of("ask", "plan", "agent").contains(task.requestMode)) {
            throw new IllegalArgumentException("dialogue_subtask requires a valid request_mode");
        }
        if (task.childRunId == null || task.childRunId.isBlank()
                || task.sessionId == null || task.sessionId.isBlank()
                || task.sourceRunId == null || task.sourceRunId.isBlank()) {
            throw new IllegalArgumentException("dialogue_subtask requires child and parent run correlation");
        }
        JsonNode command = task.payload.path("command_payload").isObject()
                ? task.payload.path("command_payload") : task.payload.path("command");
        JsonNode availableTools = command.get("available_tools");
        SubtaskCapabilitySnapshot snapshot = SubtaskCapabilitySnapshot.fromFeatures(
                command.get("features"), availableTools == null ? mapper.createArrayNode() : availableTools);
        ChatRequest request = new ChatRequest();
        request.setInputValue(task.inputValue);
        request.setRequestType(task.requestMode);
        request.setRunId(task.childRunId);
        request.setParentId(task.sourceRunId);
        snapshot.apply(request);
        Set<String> allowedServers = Set.copyOf(snapshot.getMcpServerNames());
        ObjectNode resolved = mcpResolver.resolve(task.flowId, allowedServers);
        ObjectNode filtered = mapper.createObjectNode();
        if (resolved != null) {
            resolved.fields().forEachRemaining(entry -> {
                if (allowedServers.contains(entry.getKey())) {
                    filtered.set(entry.getKey(), entry.getValue() == null ? null : entry.getValue().deepCopy());
                }
            });
        }
        request.setClientMcpServers(filtered);
        return request;
    }

    void flushResumeReports(ReportOutbox outbox) {
        outbox.list(ReportOutbox.Type.CHECKPOINT_TOOL_RESULTS).stream()
                .filter(ReportOutbox.Entry::isDue)
                .map(ReportOutbox.Entry::getFlowId)
                .distinct()
                .forEach(flowId -> agents.computeIfAbsent(flowId, agentFactory).flushResumeReports());
    }

    @Override
    public void close() {
        agents.values().forEach(TaiyiAgent::shutdown);
        agents.clear();
        sessions.clear();
    }

    private final class SessionExecution {
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

        private synchronized ObjectNode run(CollaborationPlugin.TaskDispatch task) throws Exception {
            text.setLength(0);
            protocolError.set(null);
            transportError.set(null);
            SessionControl control = new SessionControl(session);
            controlRegistered.accept(task, control);
            try {
                if ("dialogue_subtask".equals(task.taskKind)) {
                    session.chat(chatRequest(task));
                } else {
                    session.chat(task.inputValue);
                }
                while (true) {
                    try {
                        session.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw exception;
                    }
                    SessionState state = session.getState();
                    if (state == SessionState.PAUSED) {
                        control.awaitResume();
                        continue;
                    }
                    if (state == SessionState.CANCELLED || state == SessionState.INTERRUPTED) {
                        throw new TaskCancelledException();
                    }
                    if (state == SessionState.CONNECTING || state == SessionState.STREAMING
                            || state == SessionState.TOOL_CALLING || state == SessionState.PAUSING
                            || state == SessionState.RESUMING || state == SessionState.RESTARTING) {
                        continue;
                    }
                    String error = protocolError.get();
                    if (error != null) {
                        throw new IllegalStateException("Agent session failed: " + error);
                    }
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
            } finally {
                controlUnregistered.accept(task, control);
            }
        }
    }

    private static final class SessionControl implements TaskControl {
        private final AgentSession session;
        private final Object monitor = new Object();
        private volatile boolean valid = true;

        private SessionControl(AgentSession session) {
            this.session = session;
        }

        @Override
        public void pause() throws Exception {
            if (valid) {
                session.pause();
            }
        }

        @Override
        public void resume() throws Exception {
            if (valid) {
                session.resume();
                synchronized (monitor) {
                    monitor.notifyAll();
                }
            }
        }

        @Override
        public void cancel() throws Exception {
            if (valid) {
                session.cancel();
                synchronized (monitor) {
                    monitor.notifyAll();
                }
            }
        }

        private void awaitResume() throws InterruptedException {
            synchronized (monitor) {
                while (valid && session.getState() == SessionState.PAUSED) {
                    monitor.wait();
                }
            }
        }

        @Override
        public void invalidate() {
            valid = false;
            synchronized (monitor) {
                monitor.notifyAll();
            }
        }
    }
}
