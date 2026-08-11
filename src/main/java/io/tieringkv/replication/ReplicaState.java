package io.tieringkv.replication;

/** 副本进度（ADR-0108）：已应用 seq 与滞后。 */
public record ReplicaState(String replicaId, long appliedSeq,
                           long appliedAtMillis) {

    public long lagMillis(long nowMillis) {
        return Math.max(0, nowMillis - appliedAtMillis);
    }
}
