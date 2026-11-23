package io.tieringkv.transaction.geo;

import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.concurrent.CompletableFuture;

/** 地域 RPC 传输（ADR-0109）：跨地域 participant 调用抽象。 */
public interface GeoRpcTransport {

    CompletableFuture<TxnMessages.Response> prewrite(
            String region, TxnMessages.Prewrite request);

    CompletableFuture<TxnMessages.Response> commit(
            String region, TxnMessages.Commit request);

    CompletableFuture<TxnMessages.Response> rollback(
            String region, TxnMessages.Rollback request);
}
