package io.tieringkv.transaction.router;

import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;
import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.rpc.TxnParticipantRpcHandler;

import java.util.concurrent.CompletableFuture;

/** 本地直调传输（ADR-0083）：测试/单机复用同一参与者路径。 */
public final class LocalTxnTransport implements TxnTransport {

    private final TxnParticipantRpcHandler handler;

    public LocalTxnTransport(TransactionParticipant participant) {
        this.handler = new TxnParticipantRpcHandler(participant);
    }

    @Override
    public CompletableFuture<RpcFrame> call(
            String target, String regionId, RpcMessageType type,
            byte[] payload) {
        RpcFrame request = new RpcFrame(
                io.tieringkv.cluster.rpc.RequestId.next().value(),
                type, payload);
        return CompletableFuture.completedFuture(
                handler.handle(request, regionId, payload));
    }
}
