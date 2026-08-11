package io.tieringkv.transaction.router;

import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;
import io.tieringkv.transaction.rpc.TxnMessages;
import io.tieringkv.transaction.rpc.TxnRpcCodec;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongConsumer;

/**
 * Participant RPC 客户端（ADR-0083）：编码/解码 + leader 变更重试。
 */
public final class TxnParticipantClient {

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MILLIS = 10;

    private final String target;
    private final String regionId;
    private final TxnTransport transport;
    private final LongConsumer onRetry;

    public TxnParticipantClient(String target, String regionId,
                                TxnTransport transport) {
        this(target, regionId, transport, null);
    }

    public TxnParticipantClient(String target, String regionId,
                                TxnTransport transport,
                                LongConsumer onRetry) {
        this.target = target;
        this.regionId = regionId;
        this.transport = transport;
        this.onRetry = onRetry;
    }

    public CompletableFuture<TxnMessages.Response> prewrite(
            TxnMessages.Prewrite request) {
        return call(RpcMessageType.TXN_PREWRITE,
                TxnRpcCodec.encodePrewrite(request));
    }

    public CompletableFuture<TxnMessages.Response> commit(
            TxnMessages.Commit request) {
        return call(RpcMessageType.TXN_COMMIT,
                TxnRpcCodec.encodeCommit(request));
    }

    public CompletableFuture<TxnMessages.Response> rollback(
            TxnMessages.Rollback request) {
        return call(RpcMessageType.TXN_ROLLBACK,
                TxnRpcCodec.encodeRollback(request));
    }

    public CompletableFuture<TxnMessages.Response> heartbeat(
            TxnMessages.Heartbeat request) {
        return call(RpcMessageType.TXN_HEARTBEAT,
                TxnRpcCodec.encodeHeartbeat(request));
    }

    private CompletableFuture<TxnMessages.Response> call(
            RpcMessageType type, byte[] payload) {
        return callWithRetry(type, payload, 0);
    }

    private CompletableFuture<TxnMessages.Response> callWithRetry(
            RpcMessageType type, byte[] payload, int attempt) {
        return transport.call(target, regionId, type, payload)
                .thenApply(frame -> TxnRpcCodec.decodeResponse(frame.payload()))
                .handle((response, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(response);
                    }
                    Throwable root = error instanceof CompletionException
                            && error.getCause() != null
                            ? error.getCause() : error;
                    if (attempt < MAX_RETRIES
                            && root instanceof IllegalStateException) {
                        if (onRetry != null) {
                            onRetry.accept(1);
                        }
                        try {
                            Thread.sleep(RETRY_DELAY_MILLIS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return callWithRetry(type, payload, attempt + 1);
                    }
                    return CompletableFuture.<TxnMessages.Response>failedFuture(
                            error);
                }).thenCompose(future -> future);
    }
}
