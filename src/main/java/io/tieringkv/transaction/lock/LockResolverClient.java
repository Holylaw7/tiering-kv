package io.tieringkv.transaction.lock;

import io.tieringkv.mvcc.ByteKey;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.List;
import java.util.function.Function;

/** 跨 Region 锁解析客户端（ADR-0092）：CHECK / RESOLVE / 幂等。 */
public final class LockResolverClient {

    private final Function<ByteKey, RegionTxnClient> regionOf;

    public LockResolverClient(Function<ByteKey, RegionTxnClient> regionOf) {
        this.regionOf = regionOf;
    }

    public TxnMessages.Response checkStatus(byte[] key, String txnId,
                                            long startTS) {
        return regionOf.apply(new ByteKey(key))
                .checkStatus(txnId, startTS).join();
    }

    public TxnMessages.Response resolve(byte[] key, String txnId,
                                        long startTS, long commitTS,
                                        byte[] primary,
                                        List<TxnMessages.Mutation> mutations,
                                        boolean rollback) {
        return regionOf.apply(new ByteKey(key))
                .resolveLock(txnId, startTS, commitTS, primary,
                        mutations, rollback).join();
    }
}
