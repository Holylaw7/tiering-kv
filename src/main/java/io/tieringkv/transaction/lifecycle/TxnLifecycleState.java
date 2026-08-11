package io.tieringkv.transaction.lifecycle;

/** 事务生命周期（ADR-0088）。 */
public enum TxnLifecycleState {
    ACTIVE,
    PREWRITE,
    COMMITTED,
    ROLLED_BACK,
    EXPIRED
}
