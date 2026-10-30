package io.taiyiflow.sdk.core.session;

import com.fasterxml.jackson.databind.JsonNode;
import io.taiyiflow.sdk.AgentSession;
import io.taiyiflow.sdk.TaiyiAgent;
import io.taiyiflow.sdk.core.dispatch.ToolDispatcher;
import io.taiyiflow.sdk.core.state.SessionState;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.SessionHook;
import io.taiyiflow.sdk.hooks.StreamHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import io.taiyiflow.sdk.transport.envelope.ChatRequest;
import io.taiyiflow.sdk.transport.envelope.EventConstants;
import io.taiyiflow.sdk.transport.envelope.StreamEvent;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import io.taiyiflow.sdk.transport.stream.StreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of AgentSession.
 */
public class AgentSessionImpl implements AgentSession {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionImpl.class);

    private final TaiyiAgent agent;
    private final String sessionId;
    private final SessionManager sessionManager;
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.IDLE);
    private final List<StreamHook> streamHooks = new ArrayList<>();
    private final List<ToolExecutionHook> toolHooks = new ArrayList<>();
    private final List<PermissionHook> permissionHooks = new ArrayList<>();
    private final List<SessionHook> sessionHooks = new ArrayList<>();
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private volatile Future<?> runningTask;
    private volatile StreamClient.StreamHandle streamHandle;

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
        return chat(message, null);
    }

    @Override
    public AgentSession chat(String message, Map<String, JsonNode> tweaks) {
        if (!state.compareAndSet(SessionState.IDLE, SessionState.CONNECTING)) {
            throw new IllegalStateException("Session is already active: " + state.get());
        }

        notifyLifecycleStart();

        runningTask = sessionManager.getExecutor().submit(() -> {
            try {
                StreamClient client = new StreamClient(
                        agent.getBaseUrl(), agent.getApiKey(), agent.getFlowId());

                ChatRequest request = new ChatRequest(message, sessionId);
                if (tweaks != null) {
                    request.setTweaks(tweaks);
                }

                ToolDispatcher dispatcher = new ToolDispatcher(
                        agent.getPluginManager(), getToolHooks(), getPermissionHooks());

                streamHandle = client.stream(
                        request,
                        event -> handleStreamEvent(event, dispatcher),
                        reason -> {
                            log.info("Session {} stream closed: {}", sessionId, reason);
                            state.set(SessionState.DONE);
                            notifyStreamClose(reason);
                            notifyLifecycleFinish();
                            completionLatch.countDown();
                            sessionManager.removeSession(sessionId);
                        },
                        error -> {
                            log.error("Session {} stream error", sessionId, error);
                            state.set(SessionState.ERROR);
                            notifyStreamError(error);
                            completionLatch.countDown();
                            sessionManager.removeSession(sessionId);
                        }
                );

                state.set(SessionState.STREAMING);
            } catch (Exception e) {
                log.error("Session {} failed to start", sessionId, e);
                state.set(SessionState.ERROR);
                notifyStreamError(e);
                completionLatch.countDown();
                sessionManager.removeSession(sessionId);
            }
        });

        return this;
    }

    @Override
    public void interrupt() {
        SessionState current = state.get();
        if (current == SessionState.DONE || current == SessionState.ERROR) {
            return;
        }
        log.info("Interrupting session: {}", sessionId);
        state.set(SessionState.INTERRUPTED);
        if (streamHandle != null) {
            streamHandle.cancel();
        }
        if (runningTask != null) {
            runningTask.cancel(true);
        }
        notifyLifecycleInterrupt("User interrupted");
        completionLatch.countDown();
        sessionManager.removeSession(sessionId);
    }

    @Override
    public boolean isActive() {
        SessionState s = state.get();
        return s == SessionState.CONNECTING || s == SessionState.STREAMING || s == SessionState.TOOL_CALLING;
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

    public SessionState getState() {
        return state.get();
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
        List<PermissionHook> all = new ArrayList<>(agent.getGlobalPermissionHooks());
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

    private void handleStreamEvent(StreamEvent event, ToolDispatcher dispatcher) {
        // Notify all stream hooks
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
        } else if (event.isCustom()) {
            handleCustomEvent(event, dispatcher);
        } else if (event.isError()) {
            String errorMsg = event.getData() != null ? event.getData().toString() : "Unknown error";
            notifyStreamError(new RuntimeException("Agent error: " + errorMsg));
        }
    }

    private void handleCustomEvent(StreamEvent event, ToolDispatcher dispatcher) {
        JsonNode data = event.getData();
        if (data == null) return;

        String customType = data.has("type") ? data.get("type").asText() : null;
        if ("tool_call".equals(customType) || data.has("tool_calls")) {
            state.set(SessionState.TOOL_CALLING);
            JsonNode toolCalls = data.has("tool_calls") ? data.get("tool_calls") : data;
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    ToolCall call = parseToolCall(tc);
                    ToolResult result = dispatcher.dispatch(sessionId, call);
                    if (streamHandle != null) {
                        streamHandle.sendToolResult(call.getId(), result);
                    }
                }
            } else {
                ToolCall call = parseToolCall(toolCalls);
                ToolResult result = dispatcher.dispatch(sessionId, call);
                if (streamHandle != null) {
                    streamHandle.sendToolResult(call.getId(), result);
                }
            }
            state.set(SessionState.STREAMING);
        }
    }

    private ToolCall parseToolCall(JsonNode node) {
        ToolCall call = new ToolCall();
        call.setId(node.has("id") ? node.get("id").asText() : "");
        call.setName(node.has("name") ? node.get("name").asText()
                : node.has("function") && node.get("function").has("name")
                ? node.get("function").get("name").asText() : "unknown");
        call.setArguments(node.has("arguments") ? node.get("arguments")
                : node.has("function") && node.get("function").has("arguments")
                ? node.get("function").get("arguments") : null);
        return call;
    }
}
