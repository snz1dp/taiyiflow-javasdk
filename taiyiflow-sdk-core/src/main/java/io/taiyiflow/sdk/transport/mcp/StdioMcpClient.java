package io.taiyiflow.sdk.transport.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.taiyiflow.sdk.core.dispatch.ToolExecutionContext;
import io.taiyiflow.sdk.core.process.ManagedProcess;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class StdioMcpClient implements AutoCloseable {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final ScheduledExecutorService LIFETIMES = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().daemon().name("taiyiflow-mcp-lifetime").unstarted(runnable));

    private final StdioMcpServerConfig config;
    private final StdioMcpPolicy policy;
    private final Duration requestTimeout;
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Object initializeLock = new Object();
    private final Object writeLock = new Object();
    private final Object stdoutLock = new Object();
    private final AtomicBoolean terminationNotified = new AtomicBoolean();
    private volatile Runnable terminationListener = () -> { };
    private volatile ManagedProcess process;
    private volatile BufferedWriter input;
    private volatile ScheduledFuture<?> lifetime;
    private volatile boolean initialized;
    private volatile boolean closed;
    private volatile String stdoutBuffer = "";

    StdioMcpClient(StdioMcpServerConfig config, StdioMcpPolicy policy, Duration requestTimeout) {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("MCP request timeout must be positive");
        }
        this.config = config;
        this.policy = policy;
        this.requestTimeout = requestTimeout;
    }

    void onTermination(Runnable listener) {
        this.terminationListener = listener;
        if (closed) {
            notifyTermination();
        }
    }

    public List<McpToolClient.McpTool> listTools() throws Exception {
        initializeIfNeeded();
        JsonNode result = request("tools/list", mapper.createObjectNode(), null);
        List<McpToolClient.McpTool> tools = new ArrayList<>();
        for (JsonNode tool : result.path("tools")) {
            if (!tool.hasNonNull("name")) {
                continue;
            }
            JsonNode schema = tool.has("inputSchema") ? tool.get("inputSchema") : tool.get("input_schema");
            tools.add(new McpToolClient.McpTool(tool.get("name").asText(),
                    tool.path("description").asText(""), schema == null
                    ? mapper.createObjectNode() : schema.deepCopy()));
        }
        return List.copyOf(tools);
    }

    public JsonNode callTool(String toolName, JsonNode arguments) throws Exception {
        return callTool(toolName, arguments, null);
    }

    public JsonNode callTool(String toolName, JsonNode arguments,
                             ToolExecutionContext.Cancellation cancellation) throws Exception {
        initializeIfNeeded();
        ObjectNode params = mapper.createObjectNode().put("name", toolName);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        return request("tools/call", params, cancellation);
    }

    public boolean isAlive() {
        ManagedProcess current = process;
        return !closed && current != null && current.isAlive();
    }

    boolean isClosed() {
        return closed;
    }

    int pendingCount() {
        return pending.size();
    }

    private void initializeIfNeeded() throws Exception {
        if (initialized) {
            return;
        }
        synchronized (initializeLock) {
            if (initialized) {
                return;
            }
            if (closed) {
                throw new McpToolClient.McpException("MCP stdio instance is unavailable");
            }
            try {
                start();
                ObjectNode params = mapper.createObjectNode();
                params.put("protocolVersion", McpToolClient.PROTOCOL_VERSION);
                params.set("capabilities", mapper.createObjectNode());
                ObjectNode clientInfo = params.putObject("clientInfo");
                clientInfo.put("name", "taiyiflow-javasdk");
                clientInfo.put("version", "1.0.0");
                request("initialize", params, null);
                ObjectNode notification = mapper.createObjectNode();
                notification.put("jsonrpc", "2.0");
                notification.put("method", "notifications/initialized");
                write(notification);
                initialized = true;
            } catch (Exception exception) {
                close();
                throw exception;
            }
        }
    }

    private void start() throws IOException {
        policy.validate(config);
        List<String> command = new ArrayList<>();
        command.add(config.command());
        command.addAll(config.args());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(policy.workingDirectory(config).toFile());
        builder.environment().clear();
        builder.environment().putAll(policy.environment(config));
        builder.redirectErrorStream(false);
        Process started = builder.start();
        input = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
        process = new ManagedProcess(started, policy.outputTailChars(), ignored -> processExited(),
                this::appendStdout);
        lifetime = LIFETIMES.schedule(this::close, policy.maxLifetime().toMillis(), TimeUnit.MILLISECONDS);
    }

    private JsonNode request(String method, JsonNode params,
                             ToolExecutionContext.Cancellation cancellation) throws Exception {
        if (!isAlive()) {
            throw new McpToolClient.McpException("MCP stdio process is not running");
        }
        long id = requestIds.incrementAndGet();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        payload.set("params", params);
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        pending.put(id, response);
        ToolExecutionContext.CancellationRegistration registration = cancellation == null
                ? () -> { } : cancellation.onCancel(() -> response.cancel(false));
        try {
            write(payload);
            return response.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            response.cancel(false);
            throw new McpToolClient.McpException("MCP " + method + " timed out");
        } catch (CancellationException exception) {
            throw new McpToolClient.McpException("MCP " + method + " was cancelled");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw new McpToolClient.McpException("MCP " + method + " failed");
        } catch (InterruptedException exception) {
            response.cancel(false);
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            registration.close();
            pending.remove(id, response);
        }
    }

    private void write(ObjectNode payload) throws IOException {
        synchronized (writeLock) {
            if (!isAlive()) {
                throw new McpToolClient.McpException("MCP stdio process is not running");
            }
            try {
                input.write(mapper.writeValueAsString(payload));
                input.newLine();
                input.flush();
            } catch (IOException exception) {
                close();
                throw exception;
            }
        }
    }

    private void appendStdout(String chunk) {
        try {
            List<String> frames = new ArrayList<>();
            synchronized (stdoutLock) {
                stdoutBuffer += chunk;
                int newline;
                while ((newline = stdoutBuffer.indexOf('\n')) >= 0) {
                    String frame = stdoutBuffer.substring(0, newline);
                    stdoutBuffer = stdoutBuffer.substring(newline + 1);
                    if (frame.length() > policy.maxFrameChars()) {
                        throw new McpToolClient.McpException("MCP stdout frame limit exceeded");
                    }
                    if (!frame.isBlank()) {
                        frames.add(frame.strip());
                    }
                }
                if (stdoutBuffer.length() > policy.maxFrameChars()) {
                    throw new McpToolClient.McpException("MCP stdout frame limit exceeded");
                }
            }
            frames.forEach(this::handleFrame);
        } catch (Exception exception) {
            closeWithFailure(new McpToolClient.McpException("MCP stdout contained an invalid protocol frame"));
        }
    }

    private void handleFrame(String frame) {
        try {
            JsonNode rpc = mapper.readTree(frame);
            if (!"2.0".equals(rpc.path("jsonrpc").asText()) || !rpc.has("id")) {
                return;
            }
            long id = rpc.get("id").asLong(Long.MIN_VALUE);
            CompletableFuture<JsonNode> response = pending.get(id);
            if (response == null) {
                return;
            }
            if (rpc.hasNonNull("error")) {
                response.completeExceptionally(new McpToolClient.McpException(
                        "MCP request failed: " + rpc.path("error").path("message").asText("remote error")));
            } else if (rpc.has("result")) {
                response.complete(rpc.get("result"));
            } else {
                response.completeExceptionally(new McpToolClient.McpException("MCP response has no result"));
            }
        } catch (Exception exception) {
            throw new McpToolClient.McpException("Invalid MCP JSON-RPC frame");
        }
    }

    private void processExited() {
        closed = true;
        initialized = false;
        rejectPending(new McpToolClient.McpException("MCP stdio process exited"));
        notifyTermination();
    }

    private void closeWithFailure(Exception exception) {
        closed = true;
        initialized = false;
        rejectPending(exception);
        closeProcess();
        notifyTermination();
    }

    private void rejectPending(Exception exception) {
        pending.values().forEach(response -> response.completeExceptionally(exception));
        pending.clear();
    }

    @Override
    public void close() {
        closed = true;
        initialized = false;
        rejectPending(new McpToolClient.McpException("MCP stdio instance was closed"));
        closeProcess();
        notifyTermination();
    }

    private void closeProcess() {
        ScheduledFuture<?> scheduled = lifetime;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        ManagedProcess current = process;
        if (current != null) {
            current.stop(policy.stopTimeout());
        }
        try {
            BufferedWriter writer = input;
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void notifyTermination() {
        if (terminationNotified.compareAndSet(false, true)) {
            terminationListener.run();
        }
    }
}
