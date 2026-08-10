package io.tieringkv.mvcc;

/** 锁类型（ADR-0074）。 */
public enum LockType {
    WRITE,
    READ
}
