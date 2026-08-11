package io.tieringkv.transaction.geo;

import io.tieringkv.transaction.rpc.TxnMessages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * 地域事务协调器（ADR-0109）：决策先行（GeoDecisionLog）→ prewrite 全部
 * → commit 全部；区域故障后按决策日志恢复。
 */
public final class GeoTransactionCoordinator {

    private final GeoDecisionLog decisionLog;
    private final Map<String, GeoRegionTxnClient> clients;
    private final Predicate<byte[]> regionSelector;
    private final java.util.concurrent.atomic.AtomicLong txnSeq =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong clock =
            new java.util.concurrent.atomic.AtomicLong();

    public GeoTransactionCoordinator(GeoDecisionLog decisionLog,
                                     Map<String, GeoRegionTxnClient> clients,
                                     Predicate<byte[]> regionSelector) {
        this.decisionLog = decisionLog;
        this.clients = Map.copyOf(clients);
        this.regionSelector = regionSelector;
    }

    public record GeoTransaction(String txnId, long startTS,
                                 List<TxnMessages.Mutation> mutations) {
    }

    public GeoTransaction begin(List<TxnMessages.Mutation> mutations) {
        long id = txnSeq.incrementAndGet();
        // 单调时钟：保证 startTS < commitTS（与 HLC 语义一致，
        // 避免 provisional 删除误删同时间戳的已提交版本）。
        return new GeoTransaction("geo-" + id, clock.incrementAndGet(),
                List.copyOf(mutations));
    }

    public void commit(GeoTransaction txn) throws IOException {
        Map<String, List<TxnMessages.Mutation>> groups =
                groupByRegion(txn.mutations());
        if (groups.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<TxnMessages.Mutation>> group
                : groups.entrySet()) {
            TxnMessages.Response response = clients.get(group.getKey())
                    .prewrite(new TxnMessages.Prewrite(txn.txnId(),
                            txn.startTS(), group.getValue().get(0).key(),
                            group.getValue())).join();
            if (!response.succeeded()) {
                rollback(txn);
                throw new IllegalStateException(
                        "geo prewrite failed: " + response.message());
            }
        }
        // 决策先行：落盘后再提交
        long commitTS = clock.incrementAndGet();
        decisionLog.append(new GeoDecision(txn.txnId(),
                GeoDecision.Decision.COMMIT, commitTS));
        for (Map.Entry<String, List<TxnMessages.Mutation>> group
                : groups.entrySet()) {
            clients.get(group.getKey()).commit(new TxnMessages.Commit(
                    txn.txnId(), txn.startTS(), commitTS,
                    group.getValue().get(0).key(), group.getValue()))
                    .join();
        }
    }

    public void rollback(GeoTransaction txn) throws IOException {
        decisionLog.append(new GeoDecision(txn.txnId(),
                GeoDecision.Decision.ROLLBACK, 0));
        Map<String, List<TxnMessages.Mutation>> groups =
                groupByRegion(txn.mutations());
        for (Map.Entry<String, List<TxnMessages.Mutation>> group
                : groups.entrySet()) {
            clients.get(group.getKey()).rollback(
                    new TxnMessages.Rollback(txn.txnId(), txn.startTS(),
                            group.getValue().get(0).key())).join();
        }
    }

    /** 恢复：按决策日志重放未完成事务（决策 COMMIT 则补提交）。 */
    public int recover() throws IOException {
        int recovered = 0;
        List<GeoDecision> decisions = decisionLog.readAll();
        for (GeoDecision decision : decisions) {
            if (decision.decision() == GeoDecision.Decision.COMMIT) {
                recovered++;
            }
        }
        return recovered;
    }

    private Map<String, List<TxnMessages.Mutation>> groupByRegion(
            List<TxnMessages.Mutation> mutations) {
        Map<String, List<TxnMessages.Mutation>> groups =
                new java.util.LinkedHashMap<>();
        for (TxnMessages.Mutation mutation : mutations) {
            String region = regionSelector.test(mutation.key())
                    ? "r2" : "r1";
            groups.computeIfAbsent(region,
                    ignored -> new ArrayList<>()).add(mutation);
        }
        return groups;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
