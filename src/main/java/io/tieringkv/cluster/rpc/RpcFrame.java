package io.tieringkv.cluster.rpc;

/**
 * 线协议帧（ADR-0041）：
 * LENGTH(4B) | REQUEST_ID(8B) | TYPE(1B) | PAYLOAD_LENGTH(4B) | PAYLOAD。
 */
public record RpcFrame(long requestId, RpcMessageType type, byte[] payload) {

    public RpcFrame {
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
