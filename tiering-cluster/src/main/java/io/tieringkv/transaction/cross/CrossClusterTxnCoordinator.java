package io.tieringkv.transaction.cross;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.replication.cross.CrossClusterReplicationChannel;
import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 跨集群事务协调器（ADR-0339）：PREPARE 全部成功 → 决策先行落盘
 * （携带 mutations）→ COMMIT 全部；任一 PREPARE 失败决策 ROLLBACK
 * 并通知全部；{@link #recover} 对 COMMIT 决策按 mutations 重发
 * （参与者幂等）。
 */
public final class CrossClusterTxnCoordinator {

    private static final long RPC_TIMEOUT_SECONDS = 5;

    private final CrossClusterDecisionLog decisionLog;
    private final Map<String, CrossClusterReplicationChannel> channels;
    private final Function<byte[], String> clusterOf;
    private final AtomicLong txnSeq = new AtomicLong();
    private final AtomicLong clock = new AtomicLong();

    public CrossClusterTxnCoordinator(
            CrossClusterDecisionLog decisionLog,
            Map<String, CrossClusterReplicationChannel> channels,
            Function<byte[], String> clusterOf) {
        if (decisionLog == null || channels == null
                || clusterOf == null) {
            throw new IllegalArgumentException(
                    "decisionLog, channels and clusterOf required");
        }
        this.decisionLog = decisionLog;
        this.channels = Map.copyOf(channels);
        this.clusterOf = clusterOf;
    }

    public record CrossClusterTxn(String txnId, long startTS,
                                  List<TxnMessages.Mutation> mutations) {
        public CrossClusterTxn {
            mutations = List.copyOf(mutations);
        }
    }

    public CrossClusterTxn begin(List<TxnMessages.Mutation> mutations) {
        long id = txnSeq.incrementAndGet();
        return new CrossClusterTxn("cc-" + id,
                clock.incrementAndGet(), List.copyOf(mutations));
    }

    public void commit(CrossClusterTxn txn) throws Exception {
        Map<String, List<TxnMessages.Mutation>> groups =
                groupByCluster(txn.mutations());
        if (groups.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<TxnMessages.Mutation>> group
                : groups.entrySet()) {
            boolean accepted = sendPhase(group.getKey(),
                    group.getValue(), txn.txnId(), txn.startTS(),
                    ChangeEvent.EventType.TXN_PREPARE)
                    .get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!accepted) {
                rollback(txn);
                throw new IllegalStateException(
                        "cross-cluster prepare failed for "
                                + group.getKey());
            }
        }
        long commitTS = clock.incrementAndGet();
        decisionLog.append(new CrossClusterDecision(txn.txnId(),
                CrossClusterDecision.Decision.COMMIT, commitTS,
                txn.mutations()));
        for (Map.Entry<String, List<TxnMessages.Mutation>> group
                : groups.entrySet()) {
            boolean accepted = sendPhase(group.getKey(),
                    group.getValue(), txn.txnId(), commitTS,
                    ChangeEvent.EventType.TXN_COMMIT)
                    .get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!accepted) {
                // 决策已 COMMIT：recover 将重发补提交
                throw new IllegalStateException(
                        "cross-cluster commit failed for "
                                + group.getKey());
            }
        }
    }

    public void rollback(CrossClusterTxn txn) throws Exception {
        Map<String, List<TxnMessages.Mutation>> groups =
                groupByCluster(txn.mutations());
        decisionLog.append(new CrossClusterDecision(txn.txnId(),
                CrossClusterDecision.Decision.ROLLBACK, 0,
                txn.mutations()));
        for (Map.Entry<String, List<TxnMessages.Mutation>> group
                : groups.entrySet()) {
            sendPhase(group.getKey(), group.getValue(),
                    txn.txnId(), 0,
                    ChangeEvent.EventType.TXN_ROLLBACK)
                    .get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** 恢复：对 COMMIT 决策按 mutations 重发 COMMIT（幂等）。 */
    public int recover() throws Exception {
        int replayed = 0;
        for (CrossClusterDecision decision : decisionLog.readAll()) {
            if (decision.decision()
                    != CrossClusterDecision.Decision.COMMIT) {
                continue;
            }
            long seq = seqOf(decision.txnId());
            for (Map.Entry<String, List<TxnMessages.Mutation>> group
                    : groupByCluster(decision.mutations())
                    .entrySet()) {
                CrossClusterReplicationChannel channel =
                        channels.get(group.getKey());
                if (channel == null) {
                    throw new IllegalArgumentException(
                            "no channel for cluster "
                                    + group.getKey());
                }
                List<ChangeEvent> events = events(group.getKey(),
                        group.getValue(), decision.txnId(), seq,
                        decision.commitTS(),
                        ChangeEvent.EventType.TXN_COMMIT);
                channel.sendBatch(events)
                        .get(RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            replayed++;
        }
        return replayed;
    }

    private CompletableFuture<Boolean> sendPhase(
            String cluster, List<TxnMessages.Mutation> mutations,
            String txnId, long timestamp,
            ChangeEvent.EventType type) {
        long seq = seqOf(txnId);
        CrossClusterReplicationChannel channel = channels.get(cluster);
        if (channel == null) {
            throw new IllegalArgumentException(
                    "no channel for cluster " + cluster);
        }
        return channel.sendBatch(events(cluster, mutations, txnId,
                seq, timestamp, type));
    }

    private static List<ChangeEvent> events(
            String cluster, List<TxnMessages.Mutation> mutations,
            String txnId, long seq, long timestamp,
            ChangeEvent.EventType type) {
        List<ChangeEvent> events = new ArrayList<>(mutations.size());
        for (TxnMessages.Mutation mutation : mutations) {
            events.add(new ChangeEvent(seq, type, mutation.key(),
                    mutation.value(), mutation.deleted(), txnId,
                    cluster, timestamp));
        }
        return events;
    }

    private Map<String, List<TxnMessages.Mutation>> groupByCluster(
            List<TxnMessages.Mutation> mutations) {
        Map<String, List<TxnMessages.Mutation>> groups =
                new LinkedHashMap<>();
        for (TxnMessages.Mutation mutation : mutations) {
            String cluster = clusterOf.apply(mutation.key());
            if (cluster == null || cluster.isBlank()) {
                throw new IllegalArgumentException(
                        "clusterOf returned empty cluster");
            }
            groups.computeIfAbsent(cluster, ignored ->
                    new ArrayList<>()).add(mutation);
        }
        return groups;
    }

    private static long seqOf(String txnId) {
        try {
            return Long.parseLong(txnId.substring(3));
        } catch (RuntimeException e) {
            return txnId.hashCode() & 0x7fffffffL;
        }
    }
}
