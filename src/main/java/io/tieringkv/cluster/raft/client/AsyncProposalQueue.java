package io.tieringkv.cluster.raft.client;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** 有界异步提案队列（ADR-0054）：背压三态 NORMAL/WARNING/CRITICAL。 */
public final class AsyncProposalQueue {

    public enum Pressure {
        NORMAL,
        WARNING,
        CRITICAL
    }

    private final ConcurrentLinkedQueue<Proposal> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final int capacity;

    public AsyncProposalQueue(int capacity) {
        this.capacity = capacity;
    }

    /** 入队；CRITICAL 时拒绝（返回 false），WARNING 时仍接受。 */
    public boolean offer(byte[] command, AsyncProposalContext context) {
        if (pressure() == Pressure.CRITICAL) {
            return false;
        }
        queue.add(new Proposal(command, context));
        size.incrementAndGet();
        return true;
    }

    public Proposal poll() {
        Proposal proposal = queue.poll();
        if (proposal != null) {
            size.decrementAndGet();
        }
        return proposal;
    }

    public Pressure pressure() {
        int current = size.get();
        if (current >= capacity) {
            return Pressure.CRITICAL;
        }
        if (current * 100 >= capacity * 70) {
            return Pressure.WARNING;
        }
        return Pressure.NORMAL;
    }

    public int size() {
        return size.get();
    }

    public int capacity() {
        return capacity;
    }

    public record Proposal(byte[] command, AsyncProposalContext context) {
    }
}
