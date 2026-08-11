package io.tieringkv.transaction.lifecycle;

import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.transaction.router.RegionTxnClient;

import java.util.List;

/** 心跳管理（ADR-0088）：coordinator 刷新 TTL，并向 participants 续约锁。 */
public final class TxnHeartbeatManager {

    private final TransactionLifecycleManager lifecycle;
    private final List<RegionTxnClient> regions;
    private final long ttlMillis;
    private final TransactionMetricsRegistry metrics;

    public TxnHeartbeatManager(TransactionLifecycleManager lifecycle,
                               List<RegionTxnClient> regions,
                               long ttlMillis,
                               TransactionMetricsRegistry metrics) {
        this.lifecycle = lifecycle;
        this.regions = regions;
        this.ttlMillis = ttlMillis;
        this.metrics = metrics;
    }

    /** 客户端心跳：刷新 coordinator TTL，并向各 Region 发送 HEARTBEAT RPC。 */
    public boolean heartbeat(String txnId, long startTS) {
        TransactionLifecycleManager.TxnHandle handle = lifecycle.get(txnId);
        if (handle == null) {
            return false;
        }
        lifecycle.heartbeat(txnId, System.currentTimeMillis());
        for (RegionTxnClient region : regions) {
            try {
                region.heartbeat(txnId, startTS, ttlMillis).join();
            } catch (RuntimeException ignored) {
                // 单个 Region 心跳失败不致命；锁由超时恢复兜底
            }
        }
        return true;
    }
}
