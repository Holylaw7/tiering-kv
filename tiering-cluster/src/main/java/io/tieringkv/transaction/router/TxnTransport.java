package io.tieringkv.transaction.router;

import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;

import java.util.concurrent.CompletableFuture;

/** 事务传输抽象（ADR-0083）：RPC（真实 TCP）与本地（测试直调）。 */
public interface TxnTransport {

    CompletableFuture<RpcFrame> call(String target, String regionId,
                                     RpcMessageType type, byte[] payload);
}
