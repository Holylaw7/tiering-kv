package io.tieringkv.transaction.router;

import io.tieringkv.mvcc.ByteKey;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/** 单 Region 事务客户端（ADR-0083）：本 Region 内 mutations 打包。 */
public final class RegionTxnClient {

    private final String regionId;
    private final TxnParticipantClient client;
    private final Predicate<ByteKey> ownsKey;

    public RegionTxnClient(String regionId, TxnParticipantClient client,
                           Predicate<ByteKey> ownsKey) {
        this.regionId = regionId;
        this.client = client;
        this.ownsKey = ownsKey;
    }

    public String regionId() {
        return regionId;
    }

    public boolean owns(ByteKey key) {
        return ownsKey.test(key);
    }

    public CompletableFuture<TxnMessages.Response> prewrite(
            Transaction txn, List<TxnMessages.Mutation> mutations) {
        return client.prewrite(new TxnMessages.Prewrite(txn.txnId(),
                txn.startTS(), txn.primaryKeyOr(firstKey(mutations)),
                mutations));
    }

    public CompletableFuture<TxnMessages.Response> commit(
            Transaction txn, long commitTS,
            List<TxnMessages.Mutation> mutations) {
        return client.commit(new TxnMessages.Commit(txn.txnId(),
                txn.startTS(), commitTS, txn.primaryKeyOr(firstKey(mutations)),
                mutations));
    }

    public CompletableFuture<TxnMessages.Response> rollback(Transaction txn) {
        return client.rollback(new TxnMessages.Rollback(txn.txnId(),
                txn.startTS(), txn.primaryKeyOr(new byte[]{0})));
    }

    /** 恢复路径提交（ADR-0084）：元数据驱动，不依赖 Transaction 对象。 */
    public CompletableFuture<TxnMessages.Response> commit(
            String txnId, long startTS, long commitTS, byte[] primary,
            List<TxnMessages.Mutation> mutations) {
        return client.commit(new TxnMessages.Commit(txnId, startTS,
                commitTS, primary, mutations));
    }

    /** 恢复路径回滚（ADR-0084）。 */
    public CompletableFuture<TxnMessages.Response> rollback(
            String txnId, long startTS, byte[] primary) {
        return client.rollback(new TxnMessages.Rollback(
                txnId, startTS, primary));
    }

    public CompletableFuture<TxnMessages.Response> heartbeat(
            String txnId, long startTS, long ttlMillis) {
        return client.heartbeat(new TxnMessages.Heartbeat(
                txnId, startTS, ttlMillis));
    }

    /** 本 Region 归属的 mutations（写 + 删）。 */
    public List<TxnMessages.Mutation> mutations(Transaction txn) {
        List<TxnMessages.Mutation> mutations = new ArrayList<>();
        for (ByteKey key : txn.writeKeys()) {
            if (ownsKey.test(key)) {
                mutations.add(new TxnMessages.Mutation(key.key(),
                        txn.writeValue(key), false));
            }
        }
        for (ByteKey key : txn.deleteKeys()) {
            if (ownsKey.test(key)) {
                mutations.add(new TxnMessages.Mutation(
                        key.key(), null, true));
            }
        }
        return mutations;
    }

    private static byte[] firstKey(List<TxnMessages.Mutation> mutations) {
        return mutations.isEmpty() ? new byte[]{0}
                : mutations.get(0).key();
    }
}
