package com.aipanel.web;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/** 全局与房间配额在同一锁下更新，单个连接最多归还一次。 */
@Component
public class SseConnections {

    private static final int GLOBAL_LIMIT = 16;
    private static final int ROOM_LIMIT = 8;

    private int global;
    private final Map<String, Integer> perRoom = new HashMap<>();

    public synchronized Lease tryAcquire(String discussionId) {
        int count = perRoom.getOrDefault(discussionId, 0);
        if (global >= GLOBAL_LIMIT || count >= ROOM_LIMIT) {
            return null;
        }
        global++;
        perRoom.put(discussionId, count + 1);
        return new Lease(discussionId);
    }

    private synchronized void release(String discussionId) {
        perRoom.computeIfPresent(discussionId, (key, count) -> count == 1 ? null : count - 1);
        global--;
    }

    public synchronized int globalConnections() {
        return global;
    }

    public final class Lease implements AutoCloseable {

        private final String discussionId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(String discussionId) {
            this.discussionId = discussionId;
        }

        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(discussionId);
            }
        }
    }
}
