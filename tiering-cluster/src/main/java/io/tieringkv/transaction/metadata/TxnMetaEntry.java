package io.tieringkv.transaction.metadata;

import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.Map;

/** 全局事务元数据（ADR-0084）。 */
public record TxnMetaEntry(
        String txnId,
        byte[] primary,
        long startTS,
        long commitTS,
        long decisionIndex,
        State state,
        Map<String, java.util.List<TxnMessages.Mutation>> regionMutations) {

    public enum State {
        REGISTERED,
        PREPARED,
        COMMITTED,
        ROLLED_BACK
    }

    public boolean terminal() {
        return state == State.COMMITTED || state == State.ROLLED_BACK;
    }
}
