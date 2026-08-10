package io.tieringkv.mvcc;

/** 写类型（ADR-0071）：PUT / DELETE / LOCK（provisional）。 */
public enum WriteType {
    PUT,
    DELETE,
    LOCK
}
