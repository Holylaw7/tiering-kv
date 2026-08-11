package io.tieringkv.transaction.rpc;

import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;
import io.tieringkv.cluster.rpc.TxnRpcHandler;
import io.tieringkv.transaction.participant.TransactionParticipant;

/** 服务端桥接（ADR-0083）：RPC 帧 → TransactionParticipant → 响应帧。 */
public final class TxnParticipantRpcHandler implements TxnRpcHandler {

    private final TransactionParticipant participant;

    public TxnParticipantRpcHandler(TransactionParticipant participant) {
        this.participant = participant;
    }

    @Override
    public RpcFrame handle(RpcFrame request, String groupId, byte[] payload) {
        TxnMessages.Response response;
        switch (request.type()) {
            case TXN_PREWRITE -> response = participant.prewrite(
                    TxnRpcCodec.decodePrewrite(payload));
            case TXN_COMMIT -> response = participant.commit(
                    TxnRpcCodec.decodeCommit(payload));
            case TXN_ROLLBACK -> response = participant.rollback(
                    TxnRpcCodec.decodeRollback(payload));
            case TXN_HEARTBEAT -> response = participant.heartbeat(
                    TxnRpcCodec.decodeHeartbeat(payload));
            case TXN_CHECK_STATUS -> {
                io.tieringkv.transaction.rpc.TxnMessages.CheckStatus check =
                        TxnRpcCodec.decodeCheckStatus(payload);
                response = participant.checkStatus(check.txnId());
            }
            case TXN_RESOLVE_LOCK -> {
                io.tieringkv.transaction.rpc.TxnMessages.ResolveLock resolve =
                        TxnRpcCodec.decodeResolveLock(payload);
                response = participant.resolveLock(
                        new io.tieringkv.transaction.rpc.TxnMessages.Commit(
                                resolve.txnId(), resolve.startTS(),
                                resolve.commitTS(), resolve.primary(),
                                resolve.mutations()),
                        resolve.rollback());
            }
            case TXN_GET -> response = participant.get(payload);
            default -> throw new IllegalArgumentException(
                    "unexpected txn rpc type " + request.type());
        }
        return new RpcFrame(request.requestId(), request.type().responseType(),
                TxnRpcCodec.encodeResponse(response));
    }
}
