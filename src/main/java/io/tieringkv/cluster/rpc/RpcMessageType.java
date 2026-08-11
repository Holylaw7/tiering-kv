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
    ERROR(9),
    TIMEOUT_NOW(10),
    TIMEOUT_NOW_RESPONSE(11),
    TXN_PREWRITE(12),
    TXN_PREWRITE_RESPONSE(13),
    TXN_COMMIT(14),
    TXN_COMMIT_RESPONSE(15),
    TXN_ROLLBACK(16),
    TXN_ROLLBACK_RESPONSE(17),
    TXN_HEARTBEAT(18),
    TXN_HEARTBEAT_RESPONSE(19),
    TXN_METADATA(20),
    TXN_METADATA_RESPONSE(21);

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
        return this == APPEND_ENTRIES || this == REQUEST_VOTE
                || this == INSTALL_SNAPSHOT || this == TIMEOUT_NOW;
    }

    public boolean txn() {
        return wireValue >= TXN_PREWRITE.wireValue()
                && wireValue <= TXN_METADATA_RESPONSE.wireValue();
    }

    public RpcMessageType responseType() {
        return switch (this) {
            case TXN_PREWRITE -> TXN_PREWRITE_RESPONSE;
            case TXN_COMMIT -> TXN_COMMIT_RESPONSE;
            case TXN_ROLLBACK -> TXN_ROLLBACK_RESPONSE;
            case TXN_HEARTBEAT -> TXN_HEARTBEAT_RESPONSE;
            case TXN_METADATA -> TXN_METADATA_RESPONSE;
            default -> throw new IllegalArgumentException(
                    "no response type for " + this);
        };
    }
}
