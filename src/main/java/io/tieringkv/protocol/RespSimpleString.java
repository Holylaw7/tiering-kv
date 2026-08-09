package io.tieringkv.protocol;

import java.util.Objects;

/** RESP2 简单字符串，如 {@code +OK\r\n}。 */
public record RespSimpleString(String value) implements RespValue {

    public RespSimpleString {
        Objects.requireNonNull(value, "value");
    }
}
