package io.tieringkv.cluster.raft.client;

import io.tieringkv.cluster.raft.RaftNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 全异步复制客户端（ADR-0054）：提案入队 → 批量提交到 raft →
 * callback 完成；禁止 Future.get() 阻塞；leader 变更自动重试。
 */
public final class AsyncReplicationClient implements AutoCloseable {

    private static final int DEFAULT_MAX_BATCH = 64;
    private final AsyncProposalQueue queue;
    private final Supplier<RaftNode> leaderSupplier;
    private final AtomicLong requestIds = new AtomicLong();
    private final int maxBatch;
    private volatile boolean running = true;

    public AsyncReplicationClient(AsyncProposalQueue queue, Supplier<RaftNode> leaderSupplier) {
        this(queue, leaderSupplier, DEFAULT_MAX_BATCH);
    }

    public AsyncReplicationClient(AsyncProposalQueue queue,
                                  Supplier<RaftNode> leaderSupplier,
                                  int maxBatch) {
        this.queue = queue;
        this.leaderSupplier = leaderSupplier;
        this.maxBatch = Math.max(1, maxBatch);
    }

    /**
     * 提交提案；队列 CRITICAL 时立即以背压错误回调。
     * 提交线程内联批量 drain：队列中已有请求时合并为一次 proposeBatch，
     * 兼顾低延迟（单请求快速路径）与高吞吐（并发批量）。
     */
    public boolean submit(byte[] command, BiConsumer<Long, Throwable> callback) {
        long requestId = requestIds.incrementAndGet();
        AsyncProposalContext context = new AsyncProposalContext(
                requestId, 0, System.nanoTime() + 5_000_000_000L, callback);
        if (!queue.offer(command, context)) {
            callback.accept(null, new IllegalStateException("backpressure CRITICAL"));
            return false;
        }
        drainInline();
        return true;
    }

    private void drainInline() {
        if (!running) {
            return;
        }
        List<AsyncProposalQueue.Proposal> batch = pollUpTo(maxBatch);
        if (!batch.isEmpty()) {
            submitBatchWithRetry(batch, 0);
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

    private List<AsyncProposalQueue.Proposal> pollUpTo(int limit) {
        List<AsyncProposalQueue.Proposal> batch = new ArrayList<>(limit);
        AsyncProposalQueue.Proposal proposal;
        while (batch.size() < limit && (proposal = queue.poll()) != null) {
            batch.add(proposal);
        }
        return batch;
    }

    /** 整批提案；非 leader 整批重试（≤3），个体失败直接回调。 */
    private void submitBatchWithRetry(List<AsyncProposalQueue.Proposal> proposals,
                                      int attempt) {
        List<AsyncProposalQueue.Proposal> alive = new ArrayList<>(proposals.size());
        for (AsyncProposalQueue.Proposal proposal : proposals) {
            if (proposal.context().expired()) {
                proposal.context().callback().accept(null,
                        new TimeoutException("proposal deadline"));
            } else {
                alive.add(proposal);
            }
        }
        if (alive.isEmpty()) {
            return;
        }
        RaftNode leader = leaderSupplier.get();
        if (leader == null) {
            retryOrFailBatch(alive, attempt, new IllegalStateException("no leader"));
            return;
        }
        List<CompletableFuture<Long>> futures;
        try {
            futures = leader.proposeBatch(alive.stream()
                    .map(AsyncProposalQueue.Proposal::command).toList());
        } catch (RuntimeException e) {
            retryOrFailBatch(alive, attempt, e);
            return;
        }
        for (int i = 0; i < futures.size(); i++) {
            final AsyncProposalQueue.Proposal proposal = alive.get(i);
            futures.get(i).whenComplete((index, error) -> {
                if (error != null) {
                    proposal.context().callback().accept(null, error);
                } else {
                    proposal.context().callback().accept(index, null);
                }
            });
        }
    }

    private void retryOrFailBatch(List<AsyncProposalQueue.Proposal> proposals,
                                  int attempt, Throwable error) {
        if (attempt < 3 && error instanceof IllegalStateException) {
            submitBatchWithRetry(proposals, attempt + 1);
        } else {
            for (AsyncProposalQueue.Proposal proposal : proposals) {
                proposal.context().callback().accept(null, error);
            }
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
