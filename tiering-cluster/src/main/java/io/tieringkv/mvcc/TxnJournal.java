package io.tieringkv.mvcc;

import java.util.concurrent.CompletableFuture;

/** 事务记录日志（ADR-0073/0076）：可接 Raft 组。 */
public interface TxnJournal {

    CompletableFuture<Void> record(byte[] command);

    /** 内存日志（单元测试/单机）。 */
    final class InMemory implements TxnJournal {
        private final java.util.List<byte[]> records =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public CompletableFuture<Void> record(byte[] command) {
            records.add(command.clone());
            return CompletableFuture.completedFuture(null);
        }

        public int size() {
            return records.size();
        }
    }

    /** Raft 日志（ADR-0073）：经 RaftNode.propose 持久化。 */
    final class Raft implements TxnJournal {
        private final java.util.function.Function<byte[], CompletableFuture<Long>>
                proposer;

        public Raft(java.util.function.Function<byte[], CompletableFuture<Long>>
                            proposer) {
            this.proposer = proposer;
        }

        @Override
        public CompletableFuture<Void> record(byte[] command) {
            return proposer.apply(command).thenApply(index -> null);
        }
    }
}
