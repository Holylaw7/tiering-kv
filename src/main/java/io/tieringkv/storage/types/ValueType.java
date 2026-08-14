package io.tieringkv.storage.types;

/** 值类型（ADR-0276）：字符串保持裸字节，复合类型携带标签。 */
public enum ValueType {
    STRING,
    HASH,
    LIST,
    SET,
    ZSET,
    STREAM,
    JSON,
    TIME_SERIES,
    VECTOR
}
