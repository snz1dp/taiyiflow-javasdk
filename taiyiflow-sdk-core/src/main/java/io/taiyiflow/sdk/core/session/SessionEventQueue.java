package io.taiyiflow.sdk.core.session;

import io.taiyiflow.sdk.transport.envelope.StreamEvent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

final class SessionEventQueue {
    private final BlockingQueue<Item> queue;
    private final AtomicLong droppedHeartbeats = new AtomicLong();

    SessionEventQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Event queue capacity must be positive");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    void event(StreamEvent event) throws InterruptedException {
        if (event.isHeartbeat() && queue.offer(Item.event(event))) {
            return;
        }
        if (event.isHeartbeat()) {
            droppedHeartbeats.incrementAndGet();
            return;
        }
        while (!queue.offer(Item.event(event))) {
            if (!dropOneHeartbeat()) {
                queue.put(Item.event(event));
                return;
            }
        }
    }

    void close(String reason) throws InterruptedException {
        terminal(Item.close(reason));
    }

    void error(Throwable error) throws InterruptedException {
        terminal(Item.error(error));
    }

    Item take() throws InterruptedException {
        return queue.take();
    }

    void clear() {
        queue.clear();
    }

    int size() {
        return queue.size();
    }

    long getDroppedHeartbeats() {
        return droppedHeartbeats.get();
    }

    private void terminal(Item item) throws InterruptedException {
        while (!queue.offer(item)) {
            if (!dropOneHeartbeat()) {
                queue.put(item);
                return;
            }
        }
    }

    private boolean dropOneHeartbeat() {
        var iterator = queue.iterator();
        while (iterator.hasNext()) {
            Item item = iterator.next();
            if (item.event() != null && item.event().isHeartbeat()) {
                iterator.remove();
                droppedHeartbeats.incrementAndGet();
                return true;
            }
        }
        return false;
    }

    record Item(StreamEvent event, String closeReason, Throwable error) {
        static Item event(StreamEvent event) {
            return new Item(event, null, null);
        }

        static Item close(String reason) {
            return new Item(null, reason, null);
        }

        static Item error(Throwable error) {
            return new Item(null, null, error);
        }

        boolean isTerminal() {
            return event == null;
        }
    }
}
