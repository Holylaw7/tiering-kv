package io.tieringkv.cluster.rpc;

/** RPC 消息类型（ADR-0041）：Raft 三类请求与对应响应。 */
public enum RpcMessageType {
    APPEND_ENTRIES(1),
    APPEND_ENTRIES_RESPONSE(2),
    REQUEST_VOTE(3),
    REQUEST_VOTE_RESPONSE(4),
    INSTALL_SNAPSHOT(5),
    INSTALL_SNAPSHOT_RESPONSE(6),
    AUTH(7),
    AUTH_RESPONSE(8),
    ERROR(9);

    private final int wireValue;

    RpcMessageType(int wireValue) {
        this.wireValue = wireValue;
    }

    public int wireValue() {
        return wireValue;
    }

    public static RpcMessageType fromWire(int value) {
        for (RpcMessageType type : values()) {
            if (type.wireValue == value) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown rpc type " + value);
    }

    public boolean idempotent() {
        return this == APPEND_ENTRIES || this == REQUEST_VOTE || this == INSTALL_SNAPSHOT;
    }
}
