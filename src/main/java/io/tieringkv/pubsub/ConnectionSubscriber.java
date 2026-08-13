package io.tieringkv.pubsub;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 连接级订阅者（ADR-0284）：有界队列 + 丢弃计数；
 * 仅在连接事件循环线程访问。
 */
public final class ConnectionSubscriber implements Subscriber {

    private static final int DEFAULT_CAPACITY = 1024;

    private final int capacity;
    private final Queue<Message> queue;
    private final AtomicLong dropped = new AtomicLong();

    public record Message(String channel, byte[] payload) {
        public Message {
            payload = payload.clone();
        }
    }

    public ConnectionSubscriber() {
        this(DEFAULT_CAPACITY);
    }

    public ConnectionSubscriber(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                    "capacity must be positive");
        }
        this.capacity = capacity;
        this.queue = new ArrayDeque<>(capacity);
    }

    @Override
    public void onMessage(String channel, byte[] message) {
        synchronized (this) {
            if (queue.size() >= capacity) {
                queue.poll();
                dropped.incrementAndGet();
            }
            queue.offer(new Message(channel, message));
        }
    }

    public synchronized Message poll() {
        return queue.poll();
    }

    public synchronized int size() {
        return queue.size();
    }

    public long dropped() {
        return dropped.get();
    }

    public synchronized void clear() {
        queue.clear();
        dropped.set(0);
    }
}
