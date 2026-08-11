package io.tieringkv.replication;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 复制滞后跟踪（ADR-0108）：按副本记录已应用水位。 */
public final class LagTracker {

    private final Map<String, ReplicaState> states =
            new ConcurrentHashMap<>();

    public void applied(String replicaId, long seq) {
        states.put(replicaId, new ReplicaState(replicaId, seq,
                System.currentTimeMillis()));
    }

    public ReplicaState state(String replicaId) {
        return states.get(replicaId);
    }

    public long lagMillis(String replicaId, long nowMillis) {
        ReplicaState state = states.get(replicaId);
        return state == null ? Long.MAX_VALUE
                : state.lagMillis(nowMillis);
    }

    public Map<String, ReplicaState> snapshot() {
        return Map.copyOf(states);
    }
}
