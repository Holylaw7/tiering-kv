package io.tieringkv.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * RESP2 bulk string（二进制安全）。
 * 注意：{@link #bytes()} 返回内部数组，调用方不得修改；
 * Phase 2/8 引入内存池后统一管理缓冲区所有权。
 */
public final class RespBulkString implements RespValue {

    private final byte[] bytes;

    public RespBulkString(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
    }

    public byte[] bytes() {
        return bytes;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RespBulkString that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "RespBulkString(" + new String(bytes, StandardCharsets.UTF_8) + ")";
    }
}
