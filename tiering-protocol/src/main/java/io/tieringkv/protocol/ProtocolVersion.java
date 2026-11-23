package io.tieringkv.protocol;

/** 协议版本冻结（ADR-0103）：v1.0 兼容性契约。 */
public final class ProtocolVersion {

    public static final int RPC_VERSION = 1;
    public static final int RESP_VERSION = 2;
    public static final int STORAGE_FORMAT_VERSION = 1;
    public static final int META_COMMAND_VERSION = 1;

    private ProtocolVersion() {
    }
}
