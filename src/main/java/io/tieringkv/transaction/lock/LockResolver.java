package io.tieringkv.transaction.lock;

import io.tieringkv.transaction.metadata.TxnMetaEntry;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 分布式锁解析器（ADR-0089）：DetectLock → CheckPrimary →
 * ResolveCommit / ResolveRollback；解决 orphan lock / coordinator crash /
 * network timeout。
 */
public final class LockResolver {

    public enum Resolution {
        COMMITTED,
        ROLLED_BACK,
        SKIPPED
    }

    public record Result(Resolution resolution, String txnId) {
    }

    private final TransactionMetadataService metadata;
    private final Map<String, RegionTxnClient> regionsById;
    private final Function<byte[], Boolean> hasLock;
    private final TxnStatusCache statusCache;

    public LockResolver(TransactionMetadataService metadata,
                        Map<String, RegionTxnClient> regionsById,
                        Function<byte[], Boolean> hasLock,
                        TxnStatusCache statusCache) {
        this.metadata = metadata;
        this.regionsById = regionsById;
        this.hasLock = hasLock;
        this.statusCache = statusCache;
    }

    /** 解析某 key 上遗留的锁（txnId 由锁记录提供）。 */
    public Result resolve(String txnId, byte[] key, long startTS) {
        long now = System.currentTimeMillis();
        TxnStatusCache.Status cached = statusCache.get(txnId, now);
        if (cached == TxnStatusCache.Status.COMMITTED) {
            return new Result(Resolution.COMMITTED, txnId);
        }
        if (cached == TxnStatusCache.Status.ROLLED_BACK) {
            return new Result(Resolution.ROLLED_BACK, txnId);
        }
        TxnMetaEntry entry = metadata == null ? null : metadata.state()
                .get(txnId);
        if (entry != null && (entry.state() == TxnMetaEntry.State.COMMITTED
                || entry.state() == TxnMetaEntry.State.PREPARED)) {
            // primary 决策已提交：补完 commit（幂等）
            for (Map.Entry<String, List<TxnMessages.Mutation>> region
                    : entry.regionMutations().entrySet()) {
                RegionTxnClient client = regionsById.get(region.getKey());
                if (client != null) {
                    client.commit(txnId, entry.startTS(), entry.commitTS(),
                            entry.primary(), region.getValue()).join();
                }
            }
            statusCache.set(txnId, TxnStatusCache.Status.COMMITTED, now);
            return new Result(Resolution.COMMITTED, txnId);
        }
        if (entry != null && entry.state() == TxnMetaEntry.State.ROLLED_BACK) {
            rollbackLocks(txnId, startTS, now);
            return new Result(Resolution.ROLLED_BACK, txnId);
        }
        // 无元数据/仅 orphan 锁：回滚（释放锁）
        if (hasLock.apply(key)) {
            rollbackLocks(txnId, startTS, now);
            return new Result(Resolution.ROLLED_BACK, txnId);
        }
        return new Result(Resolution.SKIPPED, txnId);
    }

    private void rollbackLocks(String txnId, long startTS, long now) {
        for (RegionTxnClient client : regionsById.values()) {
            try {
                client.rollback(txnId, startTS, new byte[]{0}).join();
            } catch (RuntimeException ignored) {
                // 幂等；恢复兜底
            }
        }
        if (metadata != null) {
            try {
                metadata.rollback(txnId).join();
            } catch (RuntimeException ignored) {
                // 幂等
            }
        }
        statusCache.set(txnId, TxnStatusCache.Status.ROLLED_BACK, now);
    }
}
