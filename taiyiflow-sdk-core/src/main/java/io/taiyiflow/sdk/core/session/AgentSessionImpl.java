package io.taiyiflow.sdk.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.RecoveryPolicy;
import io.taiyiflow.sdk.StreamProtocol;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.dispatch.ApprovalBroker;
import io.taiyiflow.sdk.core.dispatch.ToolDispatcher;
import io.taiyiflow.sdk.core.state.SessionState;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import io.taiyiflow.sdk.transport.envelope.AgentLoopControlResult;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import io.taiyiflow.sdk.transport.envelope.ClientToolCallParser;
import io.taiyiflow.sdk.transport.envelope.ResumableCheckpoint;
import io.taiyiflow.sdk.transport.envelope.StreamEvent;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import io.taiyiflow.sdk.transport.rest.RestClient;
import io.taiyiflow.sdk.transport.rest.ToolResultReporter;
import io.taiyiflow.sdk.transport.stream.SseStreamClient;
import io.taiyiflow.sdk.transport.stream.StreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class AgentSessionImpl implements AgentSession {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionImpl.class);
    private static final String CLIENT_TYPE = "device";
    private static final String RETRY_CONTINUATION = "请继续完成上面的任务";
    private static final Set<String> ACTIVE_RUN_STATUSES = Set.of(
            "running", "resuming", "network_freezing", "pause_requested", "pausing", "waiting");
    private static final Set<String> COMPLETED_RUN_STATUSES = Set.of(
            "completed", "complete", "done", "success", "succeeded");
    private static final Set<String> FAILED_RUN_STATUSES = Set.of(
            "failed", "error", "expired", "producer_lost");

    private final TaiyiAgent agent;
    private volatile String sessionId;
    private final SessionManager sessionManager;
    private final Object executionLock = new Object();
    private final RunContext runContext = new RunContext();
    private final ApprovalBroker approvalBroker = new ApprovalBroker();
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.IDLE);
    private final AtomicBoolean fallbackUsed = new AtomicBoolean();
    private final List<StreamHook> streamHooks = new ArrayList<>();
    private final List<ToolExecutionHook> toolHooks = new ArrayList<>();
    private final List<PermissionHook> permissionHooks = new ArrayList<>();
    private final List<SessionHook> sessionHooks = new ArrayList<>();
    private final Map<String, ObjectNode> pendingToolResults = new ConcurrentHashMap<>();
    private volatile CountDownLatch completionLatch = new CountDownLatch(1);
    private volatile Future<?> runningTask;
    private volatile Future<?> eventTask;
    private volatile Future<?> recoveryTask;
    private volatile SessionEventQueue eventQueue;
    private volatile StreamClient.StreamHandle streamHandle;
    private volatile SseStreamClient sseClient;
    private volatile boolean serverCloseReceived;
    private volatile boolean toolExecutionObserved;
    private volatile Throwable terminalStreamError;

    public AgentSessionImpl(TaiyiAgent agent, String sessionId, SessionManager sessionManager) {
        this.agent = agent;
        this.sessionId = sessionId;
        this.sessionManager = sessionManager;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public AgentSession chat(String message) {
        return chat(message, null, List.of());
    }

    @Override
    public AgentSession chat(String message, Map<String, JsonNode> tweaks) {
        return chat(message, tweaks, List.of());
    }

    @Override
    public AgentSession chat(String message, List<String> inputFiles) {
        return chat(message, null, inputFiles);
    }

    @Override
    public AgentSession chat(String message, Map<String, JsonNode> tweaks, List<String> inputFiles) {
        synchronized (executionLock) {
            SessionState previous = state.get();
            if (previous != SessionState.IDLE && previous != SessionState.DONE
                    && previous != SessionState.CANCELLED) {
                throw new IllegalStateException("Session is already active: " + previous);
            }
            ChatRequest request = new ChatRequest(message, sessionId);
            if (tweaks != null) {
                request.setTweaks(tweaks);
            }
            if (inputFiles != null) {
                request.setInputFiles(List.copyOf(inputFiles));
            }
            if (agent.getFlowAdapter() != null) {
                agent.getFlowAdapter().customize(request);
            }
            applyClientIdentity(request);
            request.setExecutionMode("start");
            if (request.getMessageId() == null) {
                request.setMessageId(newId());
            }
            if (request.getSubscriptionId() == null) {
                request.setSubscriptionId(newId());
            }
            pendingToolResults.clear();
            runContext.begin(request);
            return startExecution(request, SessionState.CONNECTING, previous, true, false, false);
        }
    }

    @Override
    public AgentSession attach() {
        synchronized (executionLock) {
            SessionState previous = requireRecoverableState();
            if (runContext.getRunId() == null) {
                throw new IllegalStateException("No run is available to attach");
            }
            return startExecution(buildAttachRequest(null), SessionState.RESUMING, previous, false, true, false);
        }
    }

    @Override
    public AgentSession resume() {
        synchronized (executionLock) {
            SessionState previous = requireRecoverableState();
            if (runContext.getRunId() == null) {
                throw new IllegalStateException("No run is available to resume");
            }
            long generation = runContext.nextRecoveryGeneration();
            state.set(SessionState.RESUMING);
            completionLatch = new CountDownLatch(1);
            sessionManager.registerSession(this);
            recoveryTask = sessionManager.getExecutor().submit(() -> discoverAndResume(generation, previous));
            return this;
        }
    }

    @Override
    public AgentSession resume(ResumableCheckpoint checkpoint) {
        synchronized (executionLock) {
            SessionState previous = requireRecoverableState();
            validateCheckpoint(checkpoint);
            long generation = runContext.getRecoveryGeneration();
            runContext.update(checkpoint, generation);
            return startExecution(buildResumeRequest(checkpoint), SessionState.RESUMING, previous, false, true, false);
        }
    }

    @Override
    public AgentSession retry() {
        synchronized (executionLock) {
            SessionState previous = requireRestartableState(false);
            String input = runContext.isPersisted() ? RETRY_CONTINUATION : runContext.getLastInputValue();
            return startFromTemplate(input, previous);
        }
    }

    @Override
    public AgentSession repeat() {
        synchronized (executionLock) {
            SessionState previous = requireRestartableState(true);
            if (previous == SessionState.PAUSED) {
                try {
                    cancel();
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to cancel paused run before repeat", e);
                }
                previous = state.get();
            }
            return startFromTemplate(runContext.getLastInputValue(), previous);
        }
    }

    @Override
    public boolean approve(String toolCallId) {
        return approvalBroker.approve(toolCallId);
    }

    @Override
    public boolean deny(String toolCallId, String reason) {
        return approvalBroker.deny(toolCallId, reason);
    }

    private SessionState requireRestartableState(boolean allowPaused) {
        SessionState current = state.get();
        boolean allowed = current == SessionState.DONE || current == SessionState.ERROR
                || current == SessionState.CANCELLED || current == SessionState.DISCONNECTED
                || (allowPaused && current == SessionState.PAUSED);
        if (!allowed) {
            throw new IllegalStateException("Session cannot start a new run: " + current);
        }
        if (runContext.getLastInputValue() == null) {
            throw new IllegalStateException("No previous input is available");
        }
        return current;
    }

    private AgentSession startFromTemplate(String input, SessionState previous) {
        ChatRequest request = new ChatRequest(input, sessionId);
        request.setTweaks(runContext.getLastTweaks());
        request.setInputFiles(runContext.getLastInputFiles());
        request.setRequestType(runContext.getRequestType());
        if (agent.getFlowAdapter() != null) {
            agent.getFlowAdapter().customize(request);
        }
        applyClientIdentity(request);
        request.setExecutionMode("start");
        request.setRunId(newId());
        request.setMessageId(newId());
        request.setSubscriptionId(newId());
        pendingToolResults.clear();
        runContext.begin(request);
        return startExecution(request, SessionState.CONNECTING, previous, true, false, false);
    }

    private void discoverAndResume(long generation, SessionState previous) {
        try {
            List<ResumableCheckpoint> checkpoints = agent.executionControl().listResumable(agent.getFlowId(), sessionId);
            synchronized (executionLock) {
                if (!isGenerationCurrent(generation) || state.get() != SessionState.RESUMING) {
                    return;
                }
                ResumableCheckpoint checkpoint = selectCheckpoint(checkpoints);
                if (checkpoint == null) {
                    throw new IllegalStateException("No resumable checkpoint found for run " + runContext.getRunId());
                }
                runContext.update(checkpoint, generation);
                startExecution(buildResumeRequest(checkpoint), SessionState.RESUMING, previous, false, false, true);
            }
        } catch (Exception e) {
            failRecovery(generation, e);
        }
    }

    private ResumableCheckpoint selectCheckpoint(List<ResumableCheckpoint> checkpoints) {
        String checkpointId = runContext.getCheckpointId();
        String runId = runContext.getRunId();
        if (checkpointId == null && runId == null) {
            return checkpoints.size() == 1 ? checkpoints.get(0) : null;
        }
        return checkpoints.stream()
                .filter(candidate -> checkpointId != null
                        ? checkpointId.equals(candidate.getId()) : runId.equals(candidate.getRunId()))
                .findFirst()
                .orElse(null);
    }

    private void validateCheckpoint(ResumableCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.getId() == null || checkpoint.getRunId() == null) {
            throw new IllegalArgumentException("Checkpoint id and run id are required");
        }
        if (checkpoint.getSessionId() != null && !sessionId.equals(checkpoint.getSessionId())) {
            throw new IllegalArgumentException("Checkpoint belongs to another session");
        }
        if (runContext.getRunId() != null && !runContext.getRunId().equals(checkpoint.getRunId())) {
            throw new IllegalArgumentException("Checkpoint belongs to another run");
        }
    }

    private SessionState requireRecoverableState() {
        SessionState current = state.get();
        if (current != SessionState.PAUSED && current != SessionState.DISCONNECTED
                && current != SessionState.ERROR) {
            throw new IllegalStateException("Session cannot be recovered: " + current);
        }
        return current;
    }

    private ChatRequest buildAttachRequest(String executionAttemptId) {
        ChatRequest request = recoveryRequest("attach");
        request.setExecutionAttemptId(executionAttemptId != null
                ? executionAttemptId : runContext.getExecutionAttemptId());
        request.setAfterEventId(runContext.getAfterEventId());
        request.setSubscriptionId(newId());
        return request;
    }

    private ChatRequest buildResumeRequest(ResumableCheckpoint checkpoint) {
        ChatRequest request = recoveryRequest("resume");
        request.setRunId(checkpoint.getRunId());
        request.setCheckpointId(checkpoint.getId());
        request.setCheckpointVersion(checkpoint.getVersion());
        List<JsonNode> results = new ArrayList<>();
        for (String callId : checkpoint.getWaitingCallIds()) {
            ObjectNode pending = pendingToolResults.get(callId);
            if (pending != null) {
                results.add(pending.deepCopy());
            }
        }
        if (!results.isEmpty()) {
            request.setToolResults(results);
            String submission = checkpoint.getId() + ":" + checkpoint.getVersion() + ":" + results;
            request.setSubmissionId(UUID.nameUUIDFromBytes(
                    submission.getBytes(StandardCharsets.UTF_8)).toString());
        }
        request.setSubscriptionId(newId());
        return request;
    }

    private ChatRequest recoveryRequest(String mode) {
        ChatRequest request = new ChatRequest("", sessionId);
        request.setRunId(runContext.getRunId());
        request.setExecutionMode(mode);
        applyClientIdentity(request);
        return request;
    }

    private void applyClientIdentity(ChatRequest request) {
        request.setClientId(agent.getClientId());
        request.setDeviceId(agent.getDeviceId());
        request.setClientType(CLIENT_TYPE);
    }

    private AgentSession startExecution(ChatRequest request, SessionState connectingState,
                                        SessionState previous, boolean notifyStart, boolean refreshProtocol,
                                        boolean preserveCompletion) {
        long generation = runContext.nextRecoveryGeneration();
        state.set(connectingState);
        closeStreamResources();
        try {
            sessionManager.registerSession(this);
        } catch (RuntimeException e) {
            state.set(previous);
            throw e;
        }
        if (!preserveCompletion) {
            completionLatch = new CountDownLatch(1);
        }
        serverCloseReceived = false;
        terminalStreamError = null;
        if (notifyStart) {
            toolExecutionObserved = false;
        }
        fallbackUsed.set(false);
        SessionEventQueue queue = new SessionEventQueue(agent.getEventQueueCapacity());
        eventQueue = queue;
        eventTask = sessionManager.getExecutor().submit(() -> consumeEvents(queue, generation));
        if (notifyStart) {
            notifyLifecycleStart();
        }
        runningTask = sessionManager.getExecutor().submit(() -> openExecution(
                request, connectingState, generation, queue, refreshProtocol));
        return this;
    }

    private void openExecution(ChatRequest request, SessionState connectingState, long generation,
                               SessionEventQueue queue, boolean refreshProtocol) {
        Consumer<StreamEvent> onEvent = event -> enqueueEvent(generation, queue, event);
        Consumer<String> onClose = reason -> enqueueClose(generation, queue, reason);
        Consumer<Throwable> onError = error -> enqueueError(generation, queue, error);
        try {
            if ("attach".equals(request.getExecutionMode())) {
                replayPendingToolResults();
            }
            StreamProtocol protocol = refreshProtocol ? agent.refreshStreamProtocol() : agent.resolveStreamProtocol();
            SseStreamClient sse = null;
            StreamClient.StreamHandle handle = null;
            if (protocol == StreamProtocol.SSE) {
                sse = startSse(request, generation, onEvent, onClose, onError);
            } else {
                StreamClient client = new StreamClient(
                        agent.getBaseUrl(), agent.getAccessToken(), agent.getFlowId(), agent.getCredentialHeaders());
                Runnable fallback = agent.isSseFallbackEnabled()
                        ? () -> fallbackToSse(request, generation, queue, onEvent, onClose, onError)
                        : null;
                handle = client.stream(request, onEvent, onClose, onError, fallback);
                streamHandle = handle;
            }
            if (!isGenerationCurrent(generation)) {
                if (handle != null) {
                    handle.abort();
                }
                if (sse != null) {
                    sse.close();
                }
                return;
            }
            state.compareAndSet(connectingState, SessionState.STREAMING);
        } catch (Exception e) {
            log.error("Session {} failed to start", sessionId, e);
            enqueueError(generation, queue, e);
        }
    }

    private void replayPendingToolResults() {
        ToolResultReporter reporter = new ToolResultReporter(agent.getRestClient());
        for (Map.Entry<String, ObjectNode> entry : pendingToolResults.entrySet()) {
            reporter.reportPayload(entry.getValue().deepCopy());
            pendingToolResults.remove(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public AgentLoopControlResult pause() throws Exception {
        synchronized (executionLock) {
            SessionState previous = state.get();
            if (!isPausable(previous)) {
                throw new IllegalStateException("Session cannot be paused: " + previous);
            }
            state.set(SessionState.PAUSING);
            long generation = runContext.getRecoveryGeneration();
            try {
                AgentLoopControlResult result = agent.executionControl().pause(sessionId, runContext.getRunId());
                if (runContext.update(result, generation) && "paused".equals(result.getStatus())) {
                    state.set(SessionState.PAUSED);
                    closeLocalResources();
                    notifyStreamClose("Paused");
                    completionLatch.countDown();
                    sessionManager.removeSession(sessionId, this);
                }
                return result;
            } catch (Exception e) {
                state.compareAndSet(SessionState.PAUSING, previous);
                throw e;
            }
        }
    }

    @Override
    public AgentLoopControlResult cancel() throws Exception {
        synchronized (executionLock) {
            SessionState current = state.get();
            if (!isCancellable(current)) {
                throw new IllegalStateException("Session cannot be cancelled: " + current);
            }
            long generation = runContext.getRecoveryGeneration();
            AgentLoopControlResult result = agent.executionControl().cancel(sessionId, runContext.getRunId());
            if (runContext.update(result, generation)) {
                state.set(SessionState.CANCELLED);
                runContext.nextRecoveryGeneration();
                closeLocalResources();
                notifyLifecycleInterrupt("Cancelled by user");
                completionLatch.countDown();
                sessionManager.removeSession(sessionId, this);
            }
            return result;
        }
    }

    @Override
    public void interrupt() {
        synchronized (executionLock) {
            interruptLocally();
        }
    }

    private void interruptLocally() {
        SessionState current = state.get();
        if (current == SessionState.DONE || current == SessionState.ERROR
                || current == SessionState.CANCELLED || current == SessionState.PAUSED) {
            return;
        }
        log.info("Interrupting session: {}", sessionId);
        state.set(SessionState.INTERRUPTED);
        runContext.nextRecoveryGeneration();
        closeLocalResources();
        notifyLifecycleInterrupt("User interrupted");
        completionLatch.countDown();
        sessionManager.removeSession(sessionId, this);
    }

    private void closeLocalResources() {
        approvalBroker.denyAll("Session execution was interrupted");
        closeStreamResources();
        Future<?> recovery = recoveryTask;
        if (recovery != null) {
            recovery.cancel(true);
        }
    }

    private void closeStreamResources() {
        StreamClient.StreamHandle handle = streamHandle;
        if (handle != null) {
            handle.abort();
        }
        SseStreamClient sse = sseClient;
        if (sse != null) {
            sse.close();
        }
        streamHandle = null;
        sseClient = null;
        Future<?> streamTask = runningTask;
        if (streamTask != null) {
            streamTask.cancel(true);
        }
        Future<?> consumer = eventTask;
        if (consumer != null) {
            consumer.cancel(true);
        }
        SessionEventQueue queue = eventQueue;
        if (queue != null) {
            queue.clear();
        }
    }

    @Override
    public boolean isActive() {
        SessionState current = state.get();
        return current == SessionState.CONNECTING || current == SessionState.STREAMING
                || current == SessionState.TOOL_CALLING || current == SessionState.PAUSING
                || current == SessionState.RESUMING || current == SessionState.DISCONNECTED
                || current == SessionState.RESTARTING;
    }

    private static boolean isPausable(SessionState current) {
        return current == SessionState.CONNECTING || current == SessionState.STREAMING
                || current == SessionState.TOOL_CALLING;
    }

    private static boolean isCancellable(SessionState current) {
        return isPausable(current) || current == SessionState.PAUSING || current == SessionState.PAUSED
                || current == SessionState.RESUMING || current == SessionState.DISCONNECTED
                || current == SessionState.RESTARTING;
    }

    @Override
    public AgentSession onStream(StreamHook hook) {
        streamHooks.add(hook);
        return this;
    }

    @Override
    public AgentSession onToolExecution(ToolExecutionHook hook) {
        toolHooks.add(hook);
        return this;
    }

    @Override
    public AgentSession onPermission(PermissionHook hook) {
        permissionHooks.add(hook);
        return this;
    }

    @Override
    public AgentSession onLifecycle(SessionHook hook) {
        sessionHooks.add(hook);
        return this;
    }

    @Override
    public void await() throws InterruptedException {
        completionLatch.await();
    }

    @Override
    public boolean await(Duration timeout) throws InterruptedException {
        return completionLatch.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public SessionState getState() {
        return state.get();
    }

    @Override
    public RunContext getRunContext() {
        return runContext;
    }

    TaiyiAgent getAgent() {
        return agent;
    }

    List<StreamHook> getStreamHooks() {
        List<StreamHook> all = new ArrayList<>(agent.getGlobalStreamHooks());
        all.addAll(streamHooks);
        return all;
    }

    List<ToolExecutionHook> getToolHooks() {
        List<ToolExecutionHook> all = new ArrayList<>(agent.getGlobalToolHooks());
        all.addAll(toolHooks);
        return all;
    }

    List<PermissionHook> getPermissionHooks() {
        List<PermissionHook> all = new ArrayList<>();
        all.add(agent.getPermissionHook() != null ? agent.getPermissionHook() : agent.getPermissionPolicy());
        all.addAll(agent.getGlobalPermissionHooks());
        all.addAll(permissionHooks);
        return all;
    }

    private void notifyLifecycleStart() {
        for (SessionHook hook : agent.getGlobalSessionHooks()) {
            try {
                hook.onStart(sessionId);
            } catch (Exception e) {
                log.warn("SessionHook.onStart failed", e);
            }
        }
        for (SessionHook hook : sessionHooks) {
            try {
                hook.onStart(sessionId);
            } catch (Exception e) {
                log.warn("SessionHook.onStart failed", e);
            }
        }
    }

    private void notifyLifecycleFinish() {
        for (SessionHook hook : sessionHooks) {
            try {
                hook.onFinish(sessionId);
            } catch (Exception e) {
                log.warn("SessionHook.onFinish failed", e);
            }
        }
        for (SessionHook hook : agent.getGlobalSessionHooks()) {
            try {
                hook.onFinish(sessionId);
            } catch (Exception e) {
                log.warn("SessionHook.onFinish failed", e);
            }
        }
    }

    private void notifyLifecycleInterrupt(String reason) {
        for (SessionHook hook : sessionHooks) {
            try {
                hook.onInterrupt(sessionId, reason);
            } catch (Exception e) {
                log.warn("SessionHook.onInterrupt failed", e);
            }
        }
        for (SessionHook hook : agent.getGlobalSessionHooks()) {
            try {
                hook.onInterrupt(sessionId, reason);
            } catch (Exception e) {
                log.warn("SessionHook.onInterrupt failed", e);
            }
        }
    }

    private void notifyStreamError(Throwable error) {
        for (StreamHook hook : getStreamHooks()) {
            try {
                hook.onError(sessionId, error);
            } catch (Exception e) {
                log.warn("StreamHook.onError failed", e);
            }
        }
    }

    private void notifyStreamClose(String reason) {
        for (StreamHook hook : getStreamHooks()) {
            try {
                hook.onClose(sessionId, reason);
            } catch (Exception e) {
                log.warn("StreamHook.onClose failed", e);
            }
        }
    }

    private void fallbackToSse(ChatRequest request, long generation, SessionEventQueue queue,
                               Consumer<StreamEvent> onEvent, Consumer<String> onClose,
                               Consumer<Throwable> onError) {
        if (!isGenerationCurrent(generation) || eventQueue != queue || !isActive()
                || !fallbackUsed.compareAndSet(false, true)) {
            return;
        }
        log.warn("WebSocket connection failed before the initial request; falling back to SSE for session {}", sessionId);
        try {
            startSse(request, generation, onEvent, onClose, onError);
        } catch (Exception e) {
            enqueueError(generation, queue, e);
        }
    }

    private SseStreamClient startSse(ChatRequest request, long generation, Consumer<StreamEvent> onEvent,
                                     Consumer<String> onClose, Consumer<Throwable> onError) {
        SseStreamClient client = new SseStreamClient(
                agent.getBaseUrl(), agent.getAccessToken(), agent.getFlowId(), agent.getCredentialHeaders());
        sseClient = client;
        client.stream(request, onEvent, onClose, onError,
                resolvedId -> updateSessionId(generation, resolvedId));
        return client;
    }

    private void consumeEvents(SessionEventQueue queue, long generation) {
        ToolDispatcher dispatcher = new ToolDispatcher(
                agent.getPluginManager(), getToolHooks(), getPermissionHooks(),
                agent.getPermissionTimeout(), approvalBroker);
        ToolResultReporter resultReporter = new ToolResultReporter(agent.getRestClient());
        try {
            while (true) {
                SessionEventQueue.Item item = queue.take();
                if (!isGenerationCurrent(generation) || eventQueue != queue) {
                    return;
                }
                if (item.event() != null) {
                    handleStreamEvent(generation, item.event(), dispatcher, resultReporter);
                } else if (item.error() != null) {
                    handleStreamError(generation, item.error());
                } else {
                    handleStreamClose(generation, item.closeReason());
                }
                if (item.isTerminal()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            handleStreamError(generation, e);
        }
    }

    private void enqueueEvent(long generation, SessionEventQueue queue, StreamEvent event) {
        if (!isGenerationCurrent(generation) || eventQueue != queue) {
            return;
        }
        try {
            queue.event(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void enqueueClose(long generation, SessionEventQueue queue, String reason) {
        if (!isGenerationCurrent(generation) || eventQueue != queue) {
            return;
        }
        try {
            queue.close(reason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void enqueueError(long generation, SessionEventQueue queue, Throwable error) {
        if (!isGenerationCurrent(generation) || eventQueue != queue) {
            return;
        }
        try {
            queue.error(error);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isGenerationCurrent(long generation) {
        return runContext.getRecoveryGeneration() == generation;
    }

    private void updateSessionId(long generation, String resolvedSessionId) {
        if (!isGenerationCurrent(generation) || resolvedSessionId == null || resolvedSessionId.isBlank()
                || resolvedSessionId.equals(sessionId)) {
            return;
        }
        sessionManager.updateSessionId(sessionId, resolvedSessionId, this);
        sessionId = resolvedSessionId;
    }

    private void handleStreamClose(long generation, String reason) {
        if (!isGenerationCurrent(generation)) {
            return;
        }
        SessionState current = state.get();
        if (current == SessionState.INTERRUPTED || current == SessionState.CANCELLED
                || current == SessionState.PAUSED || current == SessionState.DISCONNECTED
                || current == SessionState.DONE || current == SessionState.ERROR) {
            return;
        }
        log.info("Session {} stream closed: {}", sessionId, reason);
        if (terminalStreamError != null) {
            failRecovery(generation, terminalStreamError);
            return;
        }
        if (!serverCloseReceived) {
            if (isNonRecoverableClose(reason)) {
                failRecovery(generation, new IllegalStateException(reason));
            } else {
                beginDisconnect(generation, reason, null);
            }
            return;
        }
        completeDone(generation, reason);
    }

    private void handleStreamError(long generation, Throwable error) {
        if (!isGenerationCurrent(generation)) {
            return;
        }
        SessionState current = state.get();
        if (current == SessionState.INTERRUPTED || current == SessionState.CANCELLED
                || current == SessionState.PAUSED || current == SessionState.DISCONNECTED
                || current == SessionState.DONE || current == SessionState.ERROR) {
            return;
        }
        log.error("Session {} stream error", sessionId, error);
        if (isNonRecoverable(error)) {
            failRecovery(generation, error);
        } else {
            beginDisconnect(generation, null, error);
        }
    }

    private void beginDisconnect(long generation, String closeReason, Throwable error) {
        if (!isGenerationCurrent(generation)) {
            return;
        }
        state.set(SessionState.DISCONNECTED);
        StreamClient.StreamHandle handle = streamHandle;
        if (handle != null) {
            handle.abort();
        }
        SseStreamClient sse = sseClient;
        if (sse != null) {
            sse.close();
        }
        streamHandle = null;
        sseClient = null;
        if (error != null) {
            notifyStreamError(error);
        } else {
            notifyStreamClose(closeReason);
        }
        if (agent.getRecoveryPolicy() == RecoveryPolicy.AUTO) {
            recoveryTask = sessionManager.getExecutor().submit(() -> recover(generation, error));
        } else {
            completionLatch.countDown();
        }
    }

    private void recover(long disconnectedGeneration, Throwable disconnectCause) {
        Throwable lastError = disconnectCause;
        for (int attempt = 1; attempt <= agent.getRecoveryMaxAttempts(); attempt++) {
            if (!recoveryBackoff(attempt)) {
                return;
            }
            synchronized (executionLock) {
                if (!isGenerationCurrent(disconnectedGeneration) || state.get() != SessionState.DISCONNECTED) {
                    return;
                }
                try {
                    agent.refreshStreamProtocol();
                    List<ResumableCheckpoint> checkpoints = agent.executionControl()
                            .listResumable(agent.getFlowId(), sessionId);
                    ResumableCheckpoint checkpoint = selectCheckpoint(checkpoints);
                    JsonNode session = null;
                    boolean sessionMissing = false;
                    try {
                        session = agent.sessions().get(agent.getFlowId(), sessionId);
                    } catch (RestClient.RestException e) {
                        if (e.getStatusCode() != 404) {
                            throw e;
                        }
                        sessionMissing = true;
                    }

                    if (isUsableCheckpoint(checkpoint)) {
                        if (checkpoint.isRequiresUserResume()) {
                            runContext.update(checkpoint, disconnectedGeneration);
                            pauseForUser(disconnectedGeneration);
                        } else {
                            runContext.update(checkpoint, disconnectedGeneration);
                            startExecution(buildResumeRequest(checkpoint), SessionState.RESUMING,
                                    SessionState.DISCONNECTED, false, false, true);
                        }
                        return;
                    }

                    String runStatus = text(session, "run_status");
                    if (runContext.getRunId() == null && session != null
                            && (session.path("executing").asBoolean(false)
                            || ACTIVE_RUN_STATUSES.contains(runStatus))) {
                        runContext.adoptExecution(text(session, "executing_run_id"),
                                text(session, "execution_attempt_id"), disconnectedGeneration);
                    }
                    if (isOriginalRunActive(session)) {
                        startExecution(buildAttachRequest(text(session, "execution_attempt_id")),
                                SessionState.RESUMING, SessionState.DISCONNECTED, false, false, true);
                        return;
                    }
                    if (COMPLETED_RUN_STATUSES.contains(runStatus)) {
                        completeDone(disconnectedGeneration, "Run completed on server");
                        return;
                    }
                    if ("cancelled".equals(runStatus) || "cancel_requested".equals(runStatus)) {
                        state.set(SessionState.CANCELLED);
                        runContext.nextRecoveryGeneration();
                        notifyLifecycleInterrupt("Cancelled on server");
                        completionLatch.countDown();
                        sessionManager.removeSession(sessionId, this);
                        return;
                    }

                    boolean checkpointLost = checkpoint != null && "expired".equals(checkpoint.getStatus());
                    boolean executionLost = sessionMissing || FAILED_RUN_STATUSES.contains(runStatus) || checkpointLost;
                    if (executionLost && canRestart(checkpoint)) {
                        restartAfterExecutionLoss(disconnectedGeneration, sessionMissing);
                        return;
                    }
                    if (executionLost && !agent.isAutoRestartOnExecutionLoss()) {
                        throw new IllegalStateException("Server execution is no longer recoverable");
                    }
                    lastError = new IllegalStateException("Server execution state is not yet recoverable: " + runStatus);
                } catch (Exception e) {
                    lastError = e;
                    if (isNonRecoverable(e)) {
                        failRecovery(disconnectedGeneration, e);
                        return;
                    }
                    log.warn("Recovery attempt {} failed for session {}: {}", attempt, sessionId, e.getMessage());
                }
            }
        }
        failRecovery(disconnectedGeneration, new IllegalStateException(
                "Recovery attempts exhausted", lastError));
    }

    private boolean recoveryBackoff(int attempt) {
        Duration min = agent.getRecoveryMinBackoff();
        Duration max = agent.getRecoveryMaxBackoff();
        double factor = Math.pow(2, attempt - 1);
        long baseMillis = Math.max(1, (long) Math.min(max.toMillis(), min.toMillis() * factor));
        long delay = ThreadLocalRandom.current().nextLong(Math.max(1, baseMillis / 2), baseMillis + 1);
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean isOriginalRunActive(JsonNode session) {
        if (session == null || runContext.getRunId() == null) {
            return false;
        }
        String executingRunId = text(session, "executing_run_id");
        if (!runContext.getRunId().equals(executingRunId)) {
            return false;
        }
        return session.path("executing").asBoolean(false)
                || ACTIVE_RUN_STATUSES.contains(text(session, "run_status"));
    }

    private static boolean isUsableCheckpoint(ResumableCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.getStatus() == null) {
            return false;
        }
        return Set.of("ready", "paused", "waiting").contains(checkpoint.getStatus());
    }

    private boolean canRestart(ResumableCheckpoint checkpoint) {
        if (!agent.isAutoRestartOnExecutionLoss() || runContext.getLastInputValue() == null
                || toolExecutionObserved) {
            return false;
        }
        return checkpoint == null || (checkpoint.getWaitingCallIds().isEmpty()
                && !checkpoint.hasUncertainSideEffects());
    }

    private void restartAfterExecutionLoss(long generation, boolean sessionMissing) throws Exception {
        if (sessionMissing) {
            String previousSessionId = sessionId;
            String resolvedSessionId = agent.sessions().createSessionId();
            sessionManager.updateSessionId(previousSessionId, resolvedSessionId, this);
            sessionId = resolvedSessionId;
            log.warn("Session {} was lost; restarting as session {}", previousSessionId, resolvedSessionId);
        }
        ChatRequest request = new ChatRequest(runContext.getLastInputValue(), sessionId);
        request.setTweaks(runContext.getLastTweaks());
        request.setInputFiles(runContext.getLastInputFiles());
        request.setRequestType(runContext.getRequestType());
        if (agent.getFlowAdapter() != null) {
            agent.getFlowAdapter().customize(request);
        }
        applyClientIdentity(request);
        request.setExecutionMode("start");
        request.setRunId(newId());
        request.setMessageId(newId());
        request.setSubscriptionId(newId());
        pendingToolResults.clear();
        runContext.begin(request);
        startExecution(request, SessionState.RESTARTING, SessionState.DISCONNECTED, false, false, true);
    }

    private void pauseForUser(long generation) {
        if (!isGenerationCurrent(generation)) {
            return;
        }
        state.set(SessionState.PAUSED);
        approvalBroker.denyAll("Execution requires user resume");
        notifyStreamClose("Execution requires user resume");
        completionLatch.countDown();
        sessionManager.removeSession(sessionId, this);
    }

    private void completeDone(long generation, String reason) {
        if (!isGenerationCurrent(generation)) {
            return;
        }
        state.set(SessionState.DONE);
        pendingToolResults.clear();
        notifyStreamClose(reason);
        notifyLifecycleFinish();
        completionLatch.countDown();
        sessionManager.removeSession(sessionId, this);
    }

    private void failRecovery(long generation, Throwable error) {
        if (!isGenerationCurrent(generation)) {
            return;
        }
        state.set(SessionState.ERROR);
        approvalBroker.denyAll("Session execution failed");
        notifyStreamError(error);
        completionLatch.countDown();
        sessionManager.removeSession(sessionId, this);
    }

    private static boolean isNonRecoverableClose(String reason) {
        return reason != null && (reason.startsWith("Authentication failed")
                || reason.startsWith("Permission, quota")
                || reason.startsWith("Flow not found")
                || reason.startsWith("Invalid WebSocket")
                || reason.startsWith("First frame timeout"));
    }

    private static boolean isNonRecoverable(Throwable error) {
        Throwable current = error;
        if (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof RestClient.RestException rest) {
            return switch (rest.getStatusCode()) {
                case 400, 401, 403, 404, 402, 422 -> true;
                default -> false;
            };
        }
        String message = current.getMessage();
        return message != null && (message.contains("SSE request failed: 400")
                || message.contains("SSE request failed: 401")
                || message.contains("SSE request failed: 403")
                || message.contains("SSE request failed: 404")
                || message.contains("SSE request failed: 422"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private void handleStreamEvent(long generation, StreamEvent event, ToolDispatcher dispatcher,
                                   ToolResultReporter resultReporter) {
        if (!isGenerationCurrent(generation)) {
            return;
        }
        runContext.update(event);
        if (event.isClose()) {
            serverCloseReceived = true;
        }
        for (StreamHook hook : getStreamHooks()) {
            try {
                hook.onEvent(sessionId, event);
            } catch (Exception e) {
                log.warn("StreamHook.onEvent failed", e);
            }
        }

        if (event.isMessage()) {
            String text = event.getMessageText();
            if (text != null) {
                for (StreamHook hook : getStreamHooks()) {
                    try {
                        hook.onChunk(sessionId, text);
                    } catch (Exception e) {
                        log.warn("StreamHook.onChunk failed", e);
                    }
                }
            }
        } else if (event.isClientTool() || event.isMcpTool() || event.isBuiltIn()) {
            handleClientToolEvent(event, dispatcher, resultReporter);
        } else if (event.isError()) {
            String errorMsg = event.getErrorMessage();
            terminalStreamError = new RuntimeException(
                    "Agent error: " + (errorMsg != null ? errorMsg : "Unknown error"));
        }
    }

    private void handleClientToolEvent(StreamEvent event, ToolDispatcher dispatcher,
                                       ToolResultReporter resultReporter) {
        SessionState current = state.get();
        if (current == SessionState.PAUSING || current == SessionState.PAUSED
                || current == SessionState.CANCELLED || current == SessionState.INTERRUPTED) {
            return;
        }
        state.set(SessionState.TOOL_CALLING);
        toolExecutionObserved = true;
        try {
            ToolCall call = ClientToolCallParser.parse(event, agent.getFlowId(), sessionId);
            ToolResult result = dispatcher.dispatch(sessionId, call);
            pendingToolResults.put(call.getId(), ToolResultReporter.buildPayload(call, result));
            resultReporter.report(call, result);
            pendingToolResults.remove(call.getId());
        } catch (Exception e) {
            notifyStreamError(e);
        } finally {
            state.compareAndSet(SessionState.TOOL_CALLING, SessionState.STREAMING);
        }
    }
}
