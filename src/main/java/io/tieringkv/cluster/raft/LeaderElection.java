package io.tieringkv.cluster.raft;

import java.util.concurrent.ThreadLocalRandom;

/** 选举定时（ADR-0038）：随机化超时，降低同时竞选概率。 */
public final class LeaderElection {

    private final long baseTimeoutMillis;
    private final long jitterMillis;

    public LeaderElection(long baseTimeoutMillis, long jitterMillis) {
        this.baseTimeoutMillis = baseTimeoutMillis;
        this.jitterMillis = jitterMillis;
    }

    public long nextTimeoutMillis() {
        return baseTimeoutMillis + ThreadLocalRandom.current().nextLong(jitterMillis + 1);
    }
}
