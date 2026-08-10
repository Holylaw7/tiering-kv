package io.tieringkv.cluster.raft.client;

import io.tieringkv.cluster.raft.RaftNode;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 全异步复制客户端（ADR-0054）：提案入队 → 批量提交到 raft →
 * callback 完成；禁止 Future.get() 阻塞；leader 变更自动重试。
 */
public final class AsyncReplicationClient implements AutoCloseable {

    private final AsyncProposalQueue queue;
    private final Supplier<RaftNode> leaderSupplier;
    private final AtomicLong requestIds = new AtomicLong();
    private volatile boolean running = true;

    public AsyncReplicationClient(AsyncProposalQueue queue, Supplier<RaftNode> leaderSupplier) {
        this.queue = queue;
        this.leaderSupplier = leaderSupplier;
    }

    /** 提交提案；队列 CRITICAL 时立即以背压错误回调。 */
    public boolean submit(byte[] command, BiConsumer<Long, Throwable> callback) {
        long requestId = requestIds.incrementAndGet();
        AsyncProposalContext context = new AsyncProposalContext(
                requestId, 0, System.nanoTime() + 5_000_000_000L, callback);
        if (!queue.offer(command, context)) {
            callback.accept(null, new IllegalStateException("backpressure CRITICAL"));
            return false;
        }
        drain();
        return true;
    }

    public void drain() {
        if (!running) {
            return;
        }
        AsyncProposalQueue.Proposal proposal;
        while ((proposal = queue.poll()) != null) {
            submitWithRetry(proposal, 0);
        }
    }

    private void submitWithRetry(AsyncProposalQueue.Proposal proposal, int attempt) {
        if (proposal.context().expired()) {
            proposal.context().callback().accept(null,
                    new TimeoutException("proposal deadline"));
            return;
        }
        RaftNode leader = leaderSupplier.get();
        if (leader == null) {
            failAndRetry(proposal, attempt, new IllegalStateException("no leader"));
            return;
        }
        try {
            leader.propose(proposal.command()).whenComplete((index, error) -> {
                if (error != null && attempt < 3
                        && error instanceof IllegalStateException) {
                    submitWithRetry(proposal, attempt + 1);
                } else if (error != null) {
                    proposal.context().callback().accept(null, error);
                } else {
                    proposal.context().callback().accept(index, null);
                }
            });
        } catch (RuntimeException e) {
            failAndRetry(proposal, attempt, e);
        }
    }

    private void failAndRetry(AsyncProposalQueue.Proposal proposal, int attempt,
                              Throwable error) {
        if (attempt < 3) {
            submitWithRetry(proposal, attempt + 1);
        } else {
            proposal.context().callback().accept(null, error);
        }
    }

    /** 测试钩子：直接提交一个已构造的提案。 */
    void submitProposalForTest(AsyncProposalQueue.Proposal proposal, int attempt) {
        submitWithRetry(proposal, attempt);
    }

    @Override
    public void close() {
        running = false;
    }
}
