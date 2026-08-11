package io.tieringkv.transaction.geo;

import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** 远程地域事务客户端（ADR-0109）：幂等重试（ALREADY 视为成功）。 */
public final class GeoRegionTxnClient {

    private final String region;
    private final GeoRpcTransport transport;
    private final int retries;

    public GeoRegionTxnClient(String region, GeoRpcTransport transport) {
        this(region, transport, 2);
    }

    public GeoRegionTxnClient(String region, GeoRpcTransport transport,
                              int retries) {
        this.region = region;
        this.transport = transport;
        this.retries = retries;
    }

    public String region() {
        return region;
    }

    public CompletableFuture<TxnMessages.Response> prewrite(
            TxnMessages.Prewrite request) {
        return withRetry(attempt -> transport.prewrite(region, request));
    }

    public CompletableFuture<TxnMessages.Response> commit(
            TxnMessages.Commit request) {
        return withRetry(attempt -> transport.commit(region, request));
    }

    public CompletableFuture<TxnMessages.Response> rollback(
            TxnMessages.Rollback request) {
        return withRetry(attempt -> transport.rollback(region, request));
    }

    private CompletableFuture<TxnMessages.Response> withRetry(
            Function<Integer, CompletableFuture<TxnMessages.Response>>
                    call) {
        CompletableFuture<TxnMessages.Response> result =
                new CompletableFuture<>();
        attempt(0, call, result);
        return result;
    }

    private void attempt(int round,
                         Function<Integer, CompletableFuture<
                                 TxnMessages.Response>> call,
                         CompletableFuture<TxnMessages.Response> result) {
        call.apply(round).whenComplete((response, error) -> {
            if (error == null && response.succeeded()) {
                result.complete(response);
                return;
            }
            if (round < retries) {
                attempt(round + 1, call, result);
            } else if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(response);
            }
        });
    }
}
