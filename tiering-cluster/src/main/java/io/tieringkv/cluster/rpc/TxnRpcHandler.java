package io.tieringkv.cluster.rpc;

/**
 * 事务 RPC 处理器（ADR-0083）：按 groupId 注册在 MultiRaftEndpoint 上，
 * 复用单端口 + 信封；返回完整响应帧（requestId 由端点保持）。
 */
public interface TxnRpcHandler {

    RpcFrame handle(RpcFrame request, String groupId, byte[] payload);
}
