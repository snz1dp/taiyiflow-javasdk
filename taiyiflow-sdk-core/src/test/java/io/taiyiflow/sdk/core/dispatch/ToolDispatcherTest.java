package io.taiyiflow.sdk.core.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.taiyiflow.sdk.core.plugin.PluginManager;
import io.taiyiflow.sdk.hooks.PermissionHook;
import io.taiyiflow.sdk.hooks.ToolExecutionHook;
import io.taiyiflow.sdk.transport.envelope.ToolCall;
import io.taiyiflow.sdk.transport.envelope.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolDispatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolCall makeCall(String name) {
        return new ToolCall("tc-1", name, mapper.createObjectNode());
    }

    @Test
    void zeroHooksUsesPluginManager() {
        PluginManager pm = new PluginManager();
        pm.register("test_tool", call -> ToolResult.success(call.getId(), mapper.createObjectNode().put("ok", true)));

        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(), List.of());
        ToolResult result = dispatcher.dispatch("s-1", makeCall("test_tool"));

        assertTrue(result.isSuccess());
        assertTrue(result.getResult().get("ok").asBoolean());
    }

    @Test
    void permissionDenyBlocksExecution() {
        PluginManager pm = new PluginManager();
        pm.register("shell", call -> ToolResult.success(call.getId(), mapper.createObjectNode()));

        PermissionHook denyHook = (sessionId, toolCall) ->
                CompletableFuture.completedFuture(PermissionHook.Decision.DENY);
        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(), List.of(denyHook));
        ToolResult result = dispatcher.dispatch("s-1", makeCall("shell"));

        assertFalse(result.isSuccess());
        assertEquals("Permission denied by hook", result.getError());
    }

    @Test
    void permissionAskCanBeDenied() throws Exception {
        PluginManager pm = new PluginManager();
        PermissionHook askHook = (sessionId, toolCall) ->
                CompletableFuture.completedFuture(PermissionHook.Decision.ASK);
        ApprovalBroker approvals = new ApprovalBroker();
        ToolDispatcher dispatcher = new ToolDispatcher(
                pm, List.of(), List.of(askHook), Duration.ofSeconds(1), approvals);

        CompletableFuture<ToolResult> pending = CompletableFuture.supplyAsync(
                () -> dispatcher.dispatch("s-1", makeCall("write_file")));
        awaitDecision(approvals, false);

        ToolResult result = pending.get(2, TimeUnit.SECONDS);
        assertFalse(result.isSuccess());
        assertEquals("not allowed", result.getError());
        assertFalse(approvals.deny("tc-1", "duplicate"));
    }

    @Test
    void permissionAskCanBeApprovedWithoutTimeout() throws Exception {
        PluginManager pm = new PluginManager();
        pm.register("write_file", call -> ToolResult.success(
                call.getId(), mapper.createObjectNode().put("written", true)));
        PermissionHook askHook = (sessionId, toolCall) ->
                CompletableFuture.completedFuture(PermissionHook.Decision.ASK);
        ApprovalBroker approvals = new ApprovalBroker();
        ToolDispatcher dispatcher = new ToolDispatcher(
                pm, List.of(), List.of(askHook), Duration.ZERO, approvals);

        CompletableFuture<ToolResult> pending = CompletableFuture.supplyAsync(
                () -> dispatcher.dispatch("s-1", makeCall("write_file")));
        awaitDecision(approvals, true);

        ToolResult result = pending.get(2, TimeUnit.SECONDS);
        assertTrue(result.isSuccess());
        assertTrue(result.getResult().required("written").asBoolean());
        assertFalse(approvals.approve("tc-1"));
    }

    @Test
    void permissionAskTimesOutWithoutExecutingPlugin() {
        AtomicInteger executions = new AtomicInteger();
        PluginManager pm = new PluginManager();
        pm.register("write_file", call -> {
            executions.incrementAndGet();
            return ToolResult.success(call.getId(), mapper.createObjectNode());
        });
        PermissionHook askHook = (sessionId, toolCall) ->
                CompletableFuture.completedFuture(PermissionHook.Decision.ASK);
        ToolDispatcher dispatcher = new ToolDispatcher(
                pm, List.of(), List.of(askHook), Duration.ofMillis(20));

        ToolResult result = dispatcher.dispatch("s-1", makeCall("write_file"));

        assertFalse(result.isSuccess());
        assertEquals("Permission check timed out", result.getError());
        assertEquals(0, executions.get());
    }

    @Test
    void takeoverSkipsBuiltinPlugin() {
        PluginManager pm = new PluginManager();
        pm.register("get_weather", call -> {
            throw new RuntimeException("Built-in should not be called");
        });

        ToolExecutionHook takeoverHook = new ToolExecutionHook() {
            @Override
            public ToolResult takeover(String sessionId, ToolCall toolCall) {
                return ToolResult.success(toolCall.getId(), mapper.createObjectNode().put("temp", 22));
            }
        };

        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(takeoverHook), List.of());
        ToolResult result = dispatcher.dispatch("s-1", makeCall("get_weather"));

        assertTrue(result.isSuccess());
        assertEquals(22, result.getResult().get("temp").asInt());
    }

    @Test
    void beforeHookCanModifyCall() {
        PluginManager pm = new PluginManager();
        pm.register("modified_tool", call -> ToolResult.success(call.getId(), mapper.createObjectNode().put("executed", true)));

        ToolExecutionHook beforeHook = new ToolExecutionHook() {
            @Override
            public ToolCall before(String sessionId, ToolCall toolCall) {
                return new ToolCall(toolCall.getId(), "modified_tool", toolCall.getArguments());
            }
        };

        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(beforeHook), List.of());
        ToolResult result = dispatcher.dispatch("s-1", makeCall("original_tool"));

        assertTrue(result.isSuccess());
        assertTrue(result.getResult().get("executed").asBoolean());
    }

    @Test
    void beforeHookNullCancelsExecution() {
        PluginManager pm = new PluginManager();
        ToolExecutionHook cancelHook = new ToolExecutionHook() {
            @Override
            public ToolCall before(String sessionId, ToolCall toolCall) {
                return null;
            }
        };

        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(cancelHook), List.of());
        ToolResult result = dispatcher.dispatch("s-1", makeCall("any"));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("cancelled"));
    }

    @Test
    void afterHookCanModifyResult() {
        PluginManager pm = new PluginManager();
        pm.register("tool", call -> ToolResult.success(call.getId(), mapper.createObjectNode().put("original", true)));

        ToolExecutionHook afterHook = new ToolExecutionHook() {
            @Override
            public ToolResult after(String sessionId, ToolCall toolCall, ToolResult result) {
                return ToolResult.success(toolCall.getId(), mapper.createObjectNode().put("modified", true));
            }
        };

        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(afterHook), List.of());
        ToolResult result = dispatcher.dispatch("s-1", makeCall("tool"));

        assertTrue(result.isSuccess());
        assertTrue(result.getResult().get("modified").asBoolean());
    }

    @Test
    void delayedPermissionAllowExecutesPlugin() {
        AtomicInteger executions = new AtomicInteger();
        PluginManager pm = new PluginManager();
        pm.register("tool", call -> {
            executions.incrementAndGet();
            return ToolResult.success(call.getId(), mapper.createObjectNode());
        });
        PermissionHook delayedAllow = (sessionId, toolCall) -> CompletableFuture.supplyAsync(
                () -> PermissionHook.Decision.ALLOW,
                CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS));
        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(), List.of(delayedAllow));

        ToolResult result = dispatcher.dispatch("s-1", makeCall("tool"));

        assertTrue(result.isSuccess());
        assertEquals(1, executions.get());
    }

    @Test
    void incompletePermissionTimesOutWithoutExecutingPlugin() {
        AtomicInteger executions = new AtomicInteger();
        PluginManager pm = new PluginManager();
        pm.register("tool", call -> {
            executions.incrementAndGet();
            return ToolResult.success(call.getId(), mapper.createObjectNode());
        });
        PermissionHook pending = (sessionId, toolCall) -> new CompletableFuture<>();
        ToolDispatcher dispatcher = new ToolDispatcher(
                pm, List.of(), List.of(pending), Duration.ofMillis(20));

        ToolResult result = dispatcher.dispatch("s-1", makeCall("tool"));

        assertFalse(result.isSuccess());
        assertEquals("Permission check timed out", result.getError());
        assertEquals(0, executions.get());
    }

    @Test
    void unknownToolReturnsError() {
        PluginManager pm = new PluginManager();
        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(), List.of());
        ToolResult result = dispatcher.dispatch("s-1", makeCall("nonexistent"));

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("No handler"));
    }

    @Test
    void hookChainOrderPermissionBeforeTakeoverAfter() {
        StringBuilder order = new StringBuilder();

        PluginManager pm = new PluginManager();
        pm.register("tool", call -> {
            order.append("execute;");
            return ToolResult.success(call.getId(), mapper.createObjectNode());
        });

        PermissionHook permission = (sessionId, toolCall) -> {
            order.append("permission;");
            return CompletableFuture.completedFuture(PermissionHook.Decision.ALLOW);
        };

        ToolExecutionHook hook = new ToolExecutionHook() {
            @Override
            public ToolCall before(String sessionId, ToolCall toolCall) {
                order.append("before;");
                return toolCall;
            }

            @Override
            public ToolResult takeover(String sessionId, ToolCall toolCall) {
                order.append("takeover(null);");
                return null;
            }

            @Override
            public ToolResult after(String sessionId, ToolCall toolCall, ToolResult result) {
                order.append("after;");
                return result;
            }
        };

        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(hook), List.of(permission));
        dispatcher.dispatch("s-1", makeCall("tool"));

        assertEquals("permission;before;takeover(null);execute;after;", order.toString());
    }

    private static void awaitDecision(ApprovalBroker approvals, boolean approve) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            boolean completed = approve ? approvals.approve("tc-1") : approvals.deny("tc-1", "not allowed");
            if (completed) {
                return;
            }
            Thread.sleep(5);
        }
        fail("Approval was not registered");
    }
}
