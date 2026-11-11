package io.taiyiflow.sdk.core.session;

import io.taiyiflow.sdk.transport.envelope.EventConstants;
import io.taiyiflow.sdk.transport.envelope.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionEventQueueTest {

    @Test
    void fullQueueDropsHeartbeatsBeforeBusinessEventsAndTerminalSignals() throws Exception {
        SessionEventQueue queue = new SessionEventQueue(2);
        StreamEvent heartbeat = event(EventConstants.HEARTBEAT);
        StreamEvent message = event(EventConstants.MESSAGE);

        queue.event(heartbeat);
        queue.event(message);
        queue.event(event(EventConstants.HEARTBEAT));
        queue.close("done");

        assertEquals(2, queue.getDroppedHeartbeats());
        assertEquals(2, queue.size());
        assertEquals(message, queue.take().event());
        SessionEventQueue.Item terminal = queue.take();
        assertNull(terminal.event());
        assertEquals("done", terminal.closeReason());
    }

    @Test
    void nonHeartbeatEventsApplyBlockingBackpressure() throws Exception {
        SessionEventQueue queue = new SessionEventQueue(1);
        StreamEvent first = event(EventConstants.MESSAGE);
        StreamEvent second = event(EventConstants.MESSAGE);
        queue.event(first);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> blocked = executor.submit(() -> {
                queue.event(second);
                return null;
            });
            assertThrows(TimeoutException.class, () -> blocked.get(50, TimeUnit.MILLISECONDS));
            assertEquals(first, queue.take().event());
            blocked.get(1, TimeUnit.SECONDS);
            assertEquals(second, queue.take().event());
        } finally {
            executor.shutdownNow();
        }
    }

    private static StreamEvent event(String type) {
        StreamEvent event = new StreamEvent();
        event.setEvent(type);
        return event;
    }
}
