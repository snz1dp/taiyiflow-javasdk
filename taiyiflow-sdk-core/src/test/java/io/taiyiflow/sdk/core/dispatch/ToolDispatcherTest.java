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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
        assertTrue(result.getError().contains("工具 write_file 的执行确认已超时"));
        assertTrue(result.getError().contains("20 毫秒"));
        assertTrue(result.getError().contains("请尝试其他不需要用户确认的方案"));
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
        assertTrue(result.getError().contains("工具 tool 的执行确认已超时"));
        assertEquals(0, executions.get());
    }

    @Test
    void confirmationTimeoutMessageReportsWaitedSeconds() {
        PluginManager pm = new PluginManager();
        PermissionHook askHook = (sessionId, toolCall) ->
                CompletableFuture.completedFuture(PermissionHook.Decision.ASK);
        ToolDispatcher dispatcher = new ToolDispatcher(
                pm, List.of(), List.of(askHook), Duration.ofSeconds(1));

        ToolResult result = dispatcher.dispatch("s-1", makeCall("write_file"));

        assertFalse(result.isSuccess());
        assertEquals("工具 write_file 的执行确认已超时（等待用户确认超过 1 秒），用户未及时响应。"
                + "请尝试其他不需要用户确认的方案，或在用户在线时重新尝试。", result.getError());
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

    @Test
    void askApprovalDoesNotOccupyExecutionPermit() throws Exception {
        ToolRuntime runtime = new ToolRuntime(new ToolRuntime.Config(
                1, 1, 4, Duration.ofSeconds(5)));
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch permissionCalled = new CountDownLatch(1);
        PluginManager pm = new PluginManager();
        pm.register("block", call -> {
            blockerStarted.countDown();
            try {
                assertTrue(releaseBlocker.await(2, TimeUnit.SECONDS));
                return ToolResult.success(call.getId(), mapper.createObjectNode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        });
        pm.register("approved", call -> ToolResult.success(call.getId(), mapper.createObjectNode()));
        PermissionHook permission = (sessionId, call) -> {
            if ("approved".equals(call.getName())) {
                permissionCalled.countDown();
                return CompletableFuture.completedFuture(PermissionHook.Decision.ASK);
            }
            return CompletableFuture.completedFuture(PermissionHook.Decision.ALLOW);
        };
        ApprovalBroker approvals = new ApprovalBroker();
        ToolDispatcher dispatcher = new ToolDispatcher(
                pm, List.of(), List.of(permission), Duration.ZERO, approvals, runtime);
        try {
            CompletableFuture<ToolResult> blocking = CompletableFuture.supplyAsync(() ->
                    dispatcher.dispatch(ToolExecutionContext.builder("flow", "blocking").build(),
                            new ToolCall("tc-block", "block", mapper.createObjectNode())));
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
            CompletableFuture<ToolResult> approved = CompletableFuture.supplyAsync(() ->
                    dispatcher.dispatch(ToolExecutionContext.builder("flow", "approved").build(),
                            new ToolCall("tc-approved", "approved", mapper.createObjectNode())));
            assertTrue(permissionCalled.await(1, TimeUnit.SECONDS));
            Thread.sleep(30);
            assertEquals(1, runtime.getScheduler().getRunningCount());
            assertEquals(0, runtime.getScheduler().getQueuedCount(),
                    "ASK approval must wait before scheduler admission");

            awaitDecision(approvals, "tc-approved", true);
            awaitQueued(runtime, 1);
            releaseBlocker.countDown();
            assertTrue(blocking.get(2, TimeUnit.SECONDS).isSuccess());
            assertTrue(approved.get(2, TimeUnit.SECONDS).isSuccess());
        } finally {
            releaseBlocker.countDown();
            runtime.close();
        }
    }

    @Test
    void beforeHookWaitDoesNotOccupyExecutionPermit() throws Exception {
        ToolRuntime runtime = new ToolRuntime(new ToolRuntime.Config(
                1, 1, 4, Duration.ofSeconds(5)));
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch beforeEntered = new CountDownLatch(1);
        CountDownLatch releaseBefore = new CountDownLatch(1);
        PluginManager pm = new PluginManager();
        pm.register("block", call -> {
            blockerStarted.countDown();
            try {
                assertTrue(releaseBlocker.await(2, TimeUnit.SECONDS));
                return ToolResult.success(call.getId(), mapper.createObjectNode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        });
        pm.register("before", call -> ToolResult.success(call.getId(), mapper.createObjectNode()));
        ToolExecutionHook beforeHook = new ToolExecutionHook() {
            @Override
            public ToolCall before(String sessionId, ToolCall toolCall) {
                if ("before".equals(toolCall.getName())) {
                    beforeEntered.countDown();
                    try {
                        assertTrue(releaseBefore.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return toolCall;
            }
        };
        ToolDispatcher dispatcher = new ToolDispatcher(
                pm, List.of(beforeHook), List.of(), Duration.ofSeconds(5), runtime);
        try {
            CompletableFuture<ToolResult> blocking = CompletableFuture.supplyAsync(() ->
                    dispatcher.dispatch("blocking", new ToolCall(
                            "tc-block", "block", mapper.createObjectNode())));
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
            CompletableFuture<ToolResult> before = CompletableFuture.supplyAsync(() ->
                    dispatcher.dispatch("before", new ToolCall(
                            "tc-before", "before", mapper.createObjectNode())));
            assertTrue(beforeEntered.await(1, TimeUnit.SECONDS));
            assertEquals(1, runtime.getScheduler().getRunningCount());
            assertEquals(0, runtime.getScheduler().getQueuedCount(),
                    "before hooks must run before scheduler admission");

            releaseBefore.countDown();
            awaitQueued(runtime, 1);
            releaseBlocker.countDown();
            assertTrue(blocking.get(2, TimeUnit.SECONDS).isSuccess());
            assertTrue(before.get(2, TimeUnit.SECONDS).isSuccess());
        } finally {
            releaseBefore.countDown();
            releaseBlocker.countDown();
            runtime.close();
        }
    }

    @Test
    void runtimeExecutionTimeoutIsReturnedAsToolError() {
        ToolRuntime runtime = new ToolRuntime(new ToolRuntime.Config(
                1, 1, 1, Duration.ofMillis(30)));
        PluginManager pm = new PluginManager();
        pm.register("slow", call -> {
            try {
                Thread.sleep(5000);
                return ToolResult.success(call.getId(), mapper.createObjectNode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        });
        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(), List.of(), runtime);
        try {
            ToolResult result = dispatcher.dispatch("session", makeCall("slow"));
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("timed out"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void capabilitySnapshotCannotBeBypassedByTakeoverHook() {
        PluginManager pm = new PluginManager();
        AtomicInteger takeovers = new AtomicInteger();
        ToolExecutionHook takeover = new ToolExecutionHook() {
            @Override
            public ToolResult takeover(String sessionId, ToolCall call) {
                takeovers.incrementAndGet();
                return ToolResult.success(call.getId(), mapper.createObjectNode());
            }
        };
        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(takeover), List.of());
        ToolExecutionContext context = ToolExecutionContext.builder("flow", "child")
                .requestMode("agent")
                .allowedTools(Set.of("search"))
                .build();

        ToolResult denied = dispatcher.dispatch(context, makeCall("write_file"));

        assertFalse(denied.isSuccess());
        assertTrue(denied.getError().contains("capability snapshot"));
        assertEquals(0, takeovers.get());
    }

    @Test
    void askAndPlanModesRejectMutatingToolsBeforeExecution() {
        PluginManager pm = new PluginManager();
        AtomicInteger executions = new AtomicInteger();
        pm.register("write_file", call -> {
            executions.incrementAndGet();
            return ToolResult.success(call.getId(), mapper.createObjectNode());
        });
        pm.register("search", call -> {
            executions.incrementAndGet();
            return ToolResult.success(call.getId(), mapper.createObjectNode());
        });
        ToolDispatcher dispatcher = new ToolDispatcher(pm, List.of(), List.of());
        for (String mode : List.of("ask", "plan")) {
            ToolExecutionContext context = ToolExecutionContext.builder("flow", "child-" + mode)
                    .requestMode(mode)
                    .build();
            ToolResult denied = dispatcher.dispatch(context, makeCall("write_file"));
            assertFalse(denied.isSuccess());
            assertTrue(denied.getError().contains("Mutating tools"));
            assertTrue(dispatcher.dispatch(context, makeCall("search")).isSuccess());
        }
        assertEquals(2, executions.get());
    }

    private static void awaitQueued(ToolRuntime runtime, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (runtime.getScheduler().getQueuedCount() == expected) {
                return;
            }
            Thread.sleep(5);
        }
        assertEquals(expected, runtime.getScheduler().getQueuedCount());
    }

    private static void awaitDecision(ApprovalBroker approvals, String callId, boolean approve)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            boolean completed = approve ? approvals.approve(callId) : approvals.deny(callId, "not allowed");
            if (completed) {
                return;
            }
            Thread.sleep(5);
        }
        fail("Approval was not registered");
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
