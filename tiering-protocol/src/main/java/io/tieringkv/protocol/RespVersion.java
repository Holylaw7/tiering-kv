package io.tieringkv.protocol;

/** RESP 协议版本（ADR-0281）：默认 RESP2，HELLO 3 切换。 */
public enum RespVersion {
    RESP2,
    RESP3
}
