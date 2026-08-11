package io.tieringkv.transaction.metadata;

import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.List;
import java.util.Map;

/** 元数据 Raft 命令（ADR-0084）：REGISTER / PREPARE / COMMIT / ROLLBACK。 */
public record TxnMetaCommand(
        Type type,
        String txnId,
        byte[] primary,
        long startTS,
        long commitTS,
        long decisionIndex,
        Map<String, List<TxnMessages.Mutation>> regionMutations) {

    public enum Type {
        REGISTER,
        PREPARE,
        COMMIT,
        ROLLBACK
    }

    public static TxnMetaCommand register(
            String txnId, byte[] primary, long startTS,
            Map<String, List<TxnMessages.Mutation>> regionMutations) {
        return new TxnMetaCommand(Type.REGISTER, txnId, primary, startTS, 0,
                -1, regionMutations);
    }

    public static TxnMetaCommand prepare(String txnId, long commitTS) {
        return new TxnMetaCommand(Type.PREPARE, txnId, null, 0, commitTS, -1,
                Map.of());
    }

    public static TxnMetaCommand commit(String txnId, long commitTS) {
        return new TxnMetaCommand(Type.COMMIT, txnId, null, 0, commitTS, -1,
                Map.of());
    }

    public static TxnMetaCommand rollback(String txnId) {
        return new TxnMetaCommand(Type.ROLLBACK, txnId, null, 0, 0, -1,
                Map.of());
    }

    /** 携带 Raft 决策索引（ADR-0087）。 */
    public TxnMetaCommand withDecisionIndex(long index) {
        return new TxnMetaCommand(type, txnId, primary, startTS, commitTS,
                index, regionMutations);
    }
}
