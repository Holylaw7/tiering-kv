package io.tieringkv.transaction.lifecycle;

/** 生命周期持久化记录（ADR-0091）。 */
public record TxnLifecycleRecord(String txnId, long startTS,
                                 long expireAtMillis,
                                 TxnLifecycleState state,
                                 long decisionIndex) {
}
