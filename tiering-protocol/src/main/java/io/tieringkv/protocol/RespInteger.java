package io.tieringkv.protocol;

/** RESP2 整数，如 {@code :42\r\n}。 */
public record RespInteger(long value) implements RespValue {
}
