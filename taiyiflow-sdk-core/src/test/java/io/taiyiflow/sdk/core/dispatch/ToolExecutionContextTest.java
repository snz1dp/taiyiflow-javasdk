package io.taiyiflow.sdk.core.dispatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionContextTest {

    @TempDir
    Path tempDir;

    @Test
    void carriesExplicitWorkerAndCancellationContext() {
        ToolExecutionContext.CancellationSource cancellation =
                new ToolExecutionContext.CancellationSource();
        ToolExecutionContext context = ToolExecutionContext.builder("flow-1", "session-1")
                .runId("run-1")
                .taskId("task-1")
                .workspace(tempDir.resolve("workspace"))
                .requestMode("worker")
                .cancellation(cancellation)
                .build();

        assertEquals("flow-1", context.getFlowId());
        assertEquals("session-1", context.getSessionId());
        assertEquals("run-1", context.getRunId());
        assertEquals("task-1", context.getTaskId());
        assertEquals(tempDir.resolve("workspace").toAbsolutePath().normalize(), context.getWorkspace());
        assertEquals("worker", context.getRequestMode());
        assertSame(cancellation, context.getCancellation());
        assertFalse(context.getCancellation().isCancellationRequested());
    }

    @Test
    void cancellationRegistrationCanBeRemovedAndLateRegistrationRunsImmediately() {
        ToolExecutionContext.CancellationSource cancellation =
                new ToolExecutionContext.CancellationSource();
        AtomicInteger earlyCalls = new AtomicInteger();
        AtomicInteger lateCalls = new AtomicInteger();
        ToolExecutionContext.CancellationRegistration registration =
                cancellation.onCancel(earlyCalls::incrementAndGet);

        registration.close();
        assertTrue(cancellation.cancel());
        assertEquals(0, earlyCalls.get());
        cancellation.onCancel(lateCalls::incrementAndGet);
        assertEquals(1, lateCalls.get());
        assertFalse(cancellation.cancel());
    }

    @Test
    void legacyStandaloneContextNeedsOnlySessionId() {
        ToolExecutionContext context = ToolExecutionContext.standalone("session-1");

        assertEquals("session-1", context.getSessionId());
        assertNull(context.getFlowId());
        assertNull(context.getRunId());
        assertNull(context.getTaskId());
        assertNull(context.getWorkspace());
        assertEquals(ToolExecutionContext.REQUEST_MODE_STANDALONE, context.getRequestMode());
        assertFalse(context.getCancellation().isCancellationRequested());
    }
}
