package io.tieringkv.protocol;

/** RESP2 空值：nil bulk（GET 未命中）与 nil array。 */
public enum RespNull implements RespValue {
    BULK_STRING,
    ARRAY
}
