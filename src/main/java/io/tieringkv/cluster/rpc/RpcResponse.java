package io.tieringkv.cluster.rpc;

/** RPC 响应（ADR-0041）：携带请求 ID 实现关联。 */
public record RpcResponse(RequestId id, RpcMessageType type, byte[] payload) {

    public RpcFrame toFrame() {
        return new RpcFrame(id.value(), type, payload);
    }

    public static RpcResponse fromFrame(RpcFrame frame) {
        return new RpcResponse(new RequestId(frame.requestId()), frame.type(), frame.payload());
    }
}
