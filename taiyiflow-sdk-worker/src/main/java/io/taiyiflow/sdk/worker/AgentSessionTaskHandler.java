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
import io.taiyiflow.sdk.transport.envelope.ResumableCheckpoint;
import io.taiyiflow.sdk.transport.envelope.StreamEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public final class AgentSessionTaskHandler implements WorkerTaskHandler, AutoCloseable {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentSessionTaskHandler.class);

    private final Function<String, TaiyiAgent> agentFactory;
    private volatile SubtaskMcpResolver mcpResolver = (flowId, names) -> mapper.createObjectNode();
    private final Map<String, TaiyiAgent> agents = new ConcurrentHashMap<>();
    private final Map<String, SessionExecution> sessions = new ConcurrentHashMap<>();
    // Per-key gate so concurrent resume notifications for one session can't race the
    // check-then-put below into two SessionExecution objects (double resume / orphaned entry).
    private final Map<String, Object> resumeGates = new ConcurrentHashMap<>();
    private final AtomicBoolean recoveryInFlight = new AtomicBoolean();
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

    /**
     * Reconnect recovery round (Electron agent-loop-recovery): single-flight, callers flush
     * callback compensation first, then every disconnected session resumes in parallel on its
     * own virtual thread. Like platform-driven resume notifications this is an explicit external
     * trigger, so it also applies under MANUAL recovery policy. A session-level failure leaves
     * that session pending for the next round and never aborts the whole round.
     */
    void recoverDisconnectedSessions() {
        if (!recoveryInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            for (SessionExecution execution : sessions.values()) {
                try {
                    if (execution.session.getState() == SessionState.DISCONNECTED) {
                        execution.session.resume();
                    }
                } catch (Exception exception) {
                    log.warn("Recovery stays pending for session {}: {}",
                            execution.session.getSessionId(), exception.getMessage());
                }
            }
        } finally {
            recoveryInFlight.set(false);
        }
    }

    long countSessionsInState(SessionState state) {
        return sessions.values().stream()
                .filter(execution -> execution.session.getState() == state)
                .count();
    }

    /**
     * Platform-driven checkpoint resume (agent_loop.resume.v1). The ack consumer receives
     * Electron-compatible results; unacknowledged failures let the platform re-deliver.
     */
    void resumeAgentLoop(CollaborationPlugin.ResumeRequest request, Consumer<String> ack) throws Exception {
        TaiyiAgent agent = agents.computeIfAbsent(request.flowId, agentFactory);
        String key = request.flowId + "\0" + request.sessionId;
        synchronized (resumeGates.computeIfAbsent(key, ignored -> new Object())) {
            SessionExecution existing = sessions.get(key);
            if (existing != null && existing.isExecuting()) {
                ack.accept("already_running");
                return;
            }
            List<ResumableCheckpoint> checkpoints =
                    agent.executionControl().listResumable(request.flowId, request.sessionId);
            ResumableCheckpoint match = checkpoints.stream()
                    .filter(candidate -> request.checkpointId.equals(candidate.getId())
                            && candidate.getVersion() == request.checkpointVersion)
                    .findFirst().orElse(null);
            if (match == null) {
                ack.accept("ignored_stale");
                return;
            }
            if (existing != null && existing.canAdoptCheckpoint()) {
                String currentRunId = existing.session.getRunContext().getRunId();
                if (currentRunId != null && !currentRunId.equals(match.getRunId())) {
                    ack.accept("ignored_stale");
                    return;
                }
                existing.resumeCheckpoint(match, ack);
                return;
            }
            if (existing != null) {
                sessions.remove(key, existing);
            }
            SessionExecution execution = new SessionExecution(agent, request.sessionId);
            sessions.put(key, execution);
            execution.resumeCheckpoint(match, ack);
        }
    }

    @Override
    public void close() {
        agents.values().forEach(TaiyiAgent::shutdown);
        agents.clear();
        sessions.clear();
        resumeGates.clear();
    }

    private final class SessionExecution {
        private final AgentSession session;
        private final StringBuilder text = new StringBuilder();
        private final AtomicReference<String> protocolError = new AtomicReference<>();
        private final AtomicReference<Throwable> transportError = new AtomicReference<>();
        // One reusable resume-ack hook (registered once below) instead of a new permanent StreamHook
        // per resume, which would accumulate on the session and leak across repeated resumes.
        private final AtomicReference<Consumer<String>> resumeAck = new AtomicReference<>();
        private final AtomicBoolean resumeAccepted = new AtomicBoolean();

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
                    Consumer<String> ack = resumeAck.get();
                    // The first stream event proves the backend processed the resume first frame.
                    if (ack != null && resumeAccepted.compareAndSet(false, true)) {
                        ack.accept("accepted");
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
                // Registration synchronously replays a pending cancel; re-check before opening the
                // stream so a cancel that arrived during preparation is never lost.
                if (control.cancelRequested) {
                    throw new TaskCancelledException();
                }
                if ("dialogue_subtask".equals(task.taskKind)) {
                    session.chat(chatRequest(task));
                } else {
                    session.chat(task.inputValue);
                }
                while (true) {
                    if (control.cancelRequested) {
                        // Covers a cancel that slipped between the pre-start check and chat().
                        if (session.isActive()) {
                            try {
                                session.cancel();
                            } catch (IllegalStateException ignored) {
                                // The run reached a non-cancellable state concurrently.
                            }
                        }
                        throw new TaskCancelledException();
                    }
                    try {
                        session.await(java.time.Duration.ofMillis(200));
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

        private boolean isExecuting() {
            SessionState state = session.getState();
            return state == SessionState.CONNECTING || state == SessionState.STREAMING
                    || state == SessionState.TOOL_CALLING || state == SessionState.PAUSING
                    || state == SessionState.PAUSED || state == SessionState.RESUMING
                    || state == SessionState.RESTARTING;
        }

        private boolean canAdoptCheckpoint() {
            SessionState state = session.getState();
            return state == SessionState.IDLE || state == SessionState.DISCONNECTED
                    || state == SessionState.ERROR;
        }

        private void resumeCheckpoint(ResumableCheckpoint checkpoint, Consumer<String> ack) {
            resumeAccepted.set(false);
            resumeAck.set(ack);
            session.resume(checkpoint);
        }
    }

    private static final class SessionControl implements TaskControl {
        private final AgentSession session;
        private final Object monitor = new Object();
        private volatile boolean valid = true;
        private volatile boolean cancelRequested;

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
                cancelRequested = true;
                // Before chat() there is no run to cancel remotely; the flag alone stops the start.
                if (session.isActive()) {
                    session.cancel();
                }
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
