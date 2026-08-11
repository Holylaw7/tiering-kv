package io.tieringkv.transaction.router;

import io.tieringkv.mvcc.ByteKey;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.transaction.lifecycle.TransactionLifecycleManager;
import io.tieringkv.transaction.metadata.TxnMetaEntry;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
import io.tieringkv.transaction.rpc.TxnMessages;
import io.tieringkv.transaction.rpc.TxnRpcException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 分布式事务路由（ADR-0083）：Begin → Prewrite RPC（全部 Region）→
 * Commit RPC（带 commitTS）→ Ack；COMMIT 决策经元数据持久化后
 * 不允许回滚，交给恢复补完。
 */
public final class DistributedTxnRouter {

    private final TimestampOracle oracle;
    private final Function<ByteKey, RegionTxnClient> regionOf;
    private final Map<String, RegionTxnClient> regionsById;
    private final TransactionMetadataService metadata;
    private final TransactionMetricsRegistry metrics;
    private final TransactionLifecycleManager lifecycle;
    private final long ttlMillis;
    private final long maxDurationMillis;
    private final AtomicLong txnIds = new AtomicLong();

    public DistributedTxnRouter(
            TimestampOracle oracle,
            Function<ByteKey, RegionTxnClient> regionOf,
            List<RegionTxnClient> regions,
            TransactionMetadataService metadata,
            TransactionMetricsRegistry metrics) {
        this(oracle, regionOf, regions, metadata, metrics, null, 60_000,
                300_000);
    }

    public DistributedTxnRouter(
            TimestampOracle oracle,
            Function<ByteKey, RegionTxnClient> regionOf,
            List<RegionTxnClient> regions,
            TransactionMetadataService metadata,
            TransactionMetricsRegistry metrics,
            TransactionLifecycleManager lifecycle,
            long ttlMillis,
            long maxDurationMillis) {
        this.oracle = oracle;
        this.regionOf = regionOf;
        this.metadata = metadata;
        this.metrics = metrics;
        this.lifecycle = lifecycle;
        this.ttlMillis = ttlMillis;
        this.maxDurationMillis = maxDurationMillis;
        Map<String, RegionTxnClient> byId = new LinkedHashMap<>();
        for (RegionTxnClient region : regions) {
            byId.put(region.regionId(), region);
        }
        this.regionsById = Map.copyOf(byId);
    }

    public Transaction begin() {
        if (metrics != null) {
            metrics.recordBegin();
        }
        Transaction txn = new Transaction("dtx-" + txnIds.incrementAndGet(),
                oracle.nextTimestamp());
        if (lifecycle != null) {
            lifecycle.begin(txn, ttlMillis, maxDurationMillis);
        }
        return txn;
    }

    /** 网络 2PC：prewrite 全成功 → metadata PREPARE → commit 全部。 */
    public void commit(Transaction txn) {
        long t0 = System.nanoTime();
        Map<String, List<TxnMessages.Mutation>> byRegion =
                regionMutations(txn);
        List<RegionTxnClient> regions = regionsFor(byRegion);
        if (metrics != null) {
            metrics.recordRegionCount(regions.size());
        }
        if (metadata != null) {
            metadata.register(txn.txnId(), primaryOf(txn, byRegion),
                    txn.startTS(), byRegion).join();
        }
        List<RegionTxnClient> prepared = new ArrayList<>();
        boolean decisionDurable = false;
        try {
            for (RegionTxnClient region : regions) {
                long p0 = System.nanoTime();
                TxnMessages.Response response = region.prewrite(txn,
                        byRegion.get(region.regionId())).join();
                if (metrics != null) {
                    metrics.recordPrepare(System.nanoTime() - p0);
                }
                if (!response.succeeded()) {
                    throw new TxnRpcException("prewrite failed on "
                            + region.regionId() + ": " + response.message());
                }
                prepared.add(region);
            }
            long commitTS = oracle.nextTimestamp();
            txn.markPrepared(commitTS);
            if (metadata != null) {
                metadata.prepare(txn.txnId(), commitTS).join();
                decisionDurable = true;
            }
            for (RegionTxnClient region : prepared) {
                TxnMessages.Response response = region.commit(txn, commitTS,
                        byRegion.get(region.regionId())).join();
                if (!response.succeeded()) {
                    throw new TxnRpcException("commit failed on "
                            + region.regionId() + ": " + response.message());
                }
            }
            if (metadata != null) {
                metadata.commit(txn.txnId(), commitTS).join();
            }
            txn.markCommitted(commitTS);
            if (lifecycle != null) {
                lifecycle.markCommitted(txn.txnId());
            }
            if (metrics != null) {
                metrics.recordCommit(System.nanoTime() - t0);
            }
        } catch (RuntimeException e) {
            if (metadata == null || !decisionDurable) {
                for (RegionTxnClient region : prepared) {
                    try {
                        region.rollback(txn).join();
                    } catch (RuntimeException ignored) {
                        // 回滚尽力而为；恢复阶段兜底
                    }
                }
                if (metadata != null) {
                    try {
                        metadata.rollback(txn.txnId()).join();
                    } catch (RuntimeException ignored) {
                        // 同上
                    }
                }
                txn.markRolledBack();
                if (lifecycle != null) {
                    lifecycle.markRolledBack(txn.txnId());
                }
                if (metrics != null) {
                    metrics.recordRollback();
                    metrics.recordConflict();
                }
            }
            throw e;
        }
    }

    public void rollback(Transaction txn) {
        Map<String, List<TxnMessages.Mutation>> byRegion =
                regionMutations(txn);
        for (RegionTxnClient region : regionsFor(byRegion)) {
            try {
                region.rollback(txn).join();
            } catch (RuntimeException ignored) {
                // 恢复兜底
            }
        }
        if (metadata != null) {
            try {
                metadata.rollback(txn.txnId()).join();
            } catch (RuntimeException ignored) {
                // 恢复兜底
            }
        }
        txn.markRolledBack();
        if (lifecycle != null) {
            lifecycle.markRolledBack(txn.txnId());
        }
        if (metrics != null) {
            metrics.recordRollback();
        }
    }

    /**
     * 协调器崩溃恢复（ADR-0084）：PREPARED/COMMITTED → 补完提交；
     * REGISTERED → 回滚；幂等 RPC 保证安全。
     */
    public RecoveryResult recover() {
        if (metadata == null) {
            return new RecoveryResult(0, 0, 0);
        }
        long t0 = System.nanoTime();
        long committed = 0;
        long rolledBack = 0;
        long skipped = 0;
        // ADR-0087：PREPARED/COMMITTED 都要补完（崩溃可能发生在
        // metadata COMMITTED 之后、participant commit 之前）；
        // REGISTERED 回滚；ROLLED_BACK 跳过。
        for (TxnMetaEntry entry : metadata.state().snapshot().values()) {
            switch (entry.state()) {
                case PREPARED, COMMITTED -> {
                    boolean ok = true;
                    boolean actuallyCommitted = false;
                    for (Map.Entry<String, List<TxnMessages.Mutation>> region
                            : entry.regionMutations().entrySet()) {
                        RegionTxnClient client = regionsById.get(
                                region.getKey());
                        if (client == null) {
                            ok = false;
                            continue;
                        }
                        TxnMessages.Response response = client.commit(
                                entry.txnId(), entry.startTS(),
                                entry.commitTS(), entry.primary(),
                                region.getValue()).join();
                        if (response.status() == TxnMessages.Status.OK) {
                            actuallyCommitted = true;
                        }
                        if (!response.succeeded()) {
                            ok = false;
                        }
                    }
                    if (ok) {
                        try {
                            metadata.commit(entry.txnId(),
                                    entry.commitTS()).join();
                        } catch (RuntimeException ignored) {
                            // 已提交则幂等
                        }
                        if (actuallyCommitted) {
                            committed++;
                        } else {
                            skipped++;
                        }
                    } else {
                        skipped++;
                    }
                }
                case REGISTERED -> {
                    for (Map.Entry<String, List<TxnMessages.Mutation>> region
                            : entry.regionMutations().entrySet()) {
                        RegionTxnClient client = regionsById.get(
                                region.getKey());
                        if (client != null) {
                            try {
                                client.rollback(entry.txnId(),
                                        entry.startTS(),
                                        entry.primary()).join();
                            } catch (RuntimeException ignored) {
                                // 幂等
                            }
                        }
                    }
                    try {
                        metadata.rollback(entry.txnId()).join();
                    } catch (RuntimeException ignored) {
                        // 幂等
                    }
                    rolledBack++;
                }
                default -> skipped++;
            }
        }
        if (metrics != null) {
            metrics.recordRecoveryTime(System.nanoTime() - t0);
            for (long i = 0; i < committed + rolledBack; i++) {
                metrics.recordRecovery();
            }
        }
        return new RecoveryResult(committed, rolledBack, skipped);
    }

    public TransactionMetadataService metadata() {
        return metadata;
    }

    public record RecoveryResult(long committed, long rolledBack,
                                 long skipped) {
    }

    private Map<String, List<TxnMessages.Mutation>> regionMutations(
            Transaction txn) {
        Map<String, List<TxnMessages.Mutation>> byRegion =
                new LinkedHashMap<>();
        for (ByteKey key : txn.writeKeys()) {
            RegionTxnClient region = regionOf.apply(key);
            byRegion.computeIfAbsent(region.regionId(),
                    ignored -> new ArrayList<>()).add(
                    new TxnMessages.Mutation(key.key(),
                            txn.writeValue(key), false));
        }
        for (ByteKey key : txn.deleteKeys()) {
            RegionTxnClient region = regionOf.apply(key);
            byRegion.computeIfAbsent(region.regionId(),
                    ignored -> new ArrayList<>()).add(
                    new TxnMessages.Mutation(key.key(), null, true));
        }
        return byRegion;
    }

    private List<RegionTxnClient> regionsFor(
            Map<String, List<TxnMessages.Mutation>> byRegion) {
        List<RegionTxnClient> regions = new ArrayList<>();
        for (String regionId : byRegion.keySet()) {
            regions.add(regionsById.get(regionId));
        }
        return regions;
    }

    private static byte[] primaryOf(
            Transaction txn,
            Map<String, List<TxnMessages.Mutation>> byRegion) {
        for (List<TxnMessages.Mutation> mutations : byRegion.values()) {
            if (!mutations.isEmpty()) {
                return mutations.get(0).key();
            }
        }
        return new byte[]{0};
    }
}
