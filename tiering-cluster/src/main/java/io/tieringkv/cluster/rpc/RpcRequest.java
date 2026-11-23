package io.tieringkv.cluster.rpc;

/** RPC 请求（ADR-0041）：请求 ID + 消息类型 + payload。 */
public record RpcRequest(RequestId id, RpcMessageType type, byte[] payload) {

    public RpcFrame toFrame() {
        return new RpcFrame(id.value(), type, payload);
    }
}
