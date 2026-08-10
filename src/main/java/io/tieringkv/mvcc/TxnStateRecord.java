package io.tieringkv.mvcc;

import java.util.List;

/** 事务状态记录（ADR-0081）：PREWRITE / COMMIT / ROLLBACK + 变更集。 */
public record TxnStateRecord(
        String txnId,
        State state,
        long startTS,
        long commitTS,
        byte[] primary,
        List<Mutation> mutations) {

    public enum State {
        PREWRITE,
        COMMIT,
        ROLLBACK
    }

    public record Mutation(byte[] key, byte[] value, boolean deleted) {
    }
}
