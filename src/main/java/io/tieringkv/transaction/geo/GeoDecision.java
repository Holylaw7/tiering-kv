package io.tieringkv.transaction.geo;

/** 地域事务决策（ADR-0109）：提交前持久化，区域故障后重放。 */
public record GeoDecision(String txnId, Decision decision, long commitTS) {

    public enum Decision {
        COMMIT,
        ROLLBACK
    }
}
