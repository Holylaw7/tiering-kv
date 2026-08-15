package io.tieringkv.transaction.cross;

import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.List;

/** 跨集群事务决策（ADR-0339）：携带 mutations 供恢复重放。 */
public record CrossClusterDecision(
        String txnId,
        Decision decision,
        long commitTS,
        List<TxnMessages.Mutation> mutations) {

    public enum Decision {
        COMMIT,
        ROLLBACK
    }

    public CrossClusterDecision {
        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException("txnId required");
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision required");
        }
        mutations = List.copyOf(mutations);
    }
}
