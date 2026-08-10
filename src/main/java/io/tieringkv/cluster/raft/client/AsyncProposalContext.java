package io.tieringkv.cluster.raft.client;

import java.util.function.BiConsumer;

/** 异步提案上下文（ADR-0054）：requestId/term/deadline/callback。 */
public record AsyncProposalContext(
        long requestId,
        long term,
        long deadlineNanos,
        BiConsumer<Long, Throwable> callback) {

    public boolean expired() {
        return System.nanoTime() > deadlineNanos;
    }
}
