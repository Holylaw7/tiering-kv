package io.tieringkv.transaction.router;

import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;

import java.util.concurrent.CompletableFuture;

/** 真实 TCP 事务传输（ADR-0083）：复用 MultiRaftEndpoint 单端口。 */
public final class RpcTxnTransport implements TxnTransport {

    private final MultiRaftEndpoint endpoint;

    public RpcTxnTransport(MultiRaftEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public CompletableFuture<RpcFrame> call(
            String target, String regionId, RpcMessageType type,
            byte[] payload) {
        return endpoint.callTxn(target, regionId, type, payload);
    }
}
