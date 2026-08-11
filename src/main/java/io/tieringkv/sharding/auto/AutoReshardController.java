package io.tieringkv.sharding.auto;

/** 自动重分片控制器（ADR-0132）：判定 + 冷却 + 熔断。 */
public final class AutoReshardController {

    public enum Decision {
        SPLIT,
        MERGE,
        NOOP
    }

    private final ReshardPolicy policy;
    private long lastActionAt;
    private int failures;
    private boolean tripped;

    public AutoReshardController(ReshardPolicy policy) {
        this.policy = policy;
    }

    public synchronized Decision decide(LoadProbe probe) {
        if (tripped) {
            return Decision.NOOP;
        }
        long now = System.currentTimeMillis();
        if (now - lastActionAt < policy.cooldownMillis()) {
            return Decision.NOOP;
        }
        Decision decision;
        if (probe.qps() > policy.splitQpsThreshold()) {
            decision = Decision.SPLIT;
        } else if (probe.qps() < policy.mergeQpsThreshold()) {
            decision = Decision.MERGE;
        } else {
            return Decision.NOOP;
        }
        lastActionAt = now;
        return decision;
    }

    public synchronized void onSuccess() {
        failures = 0;
        tripped = false;
    }

    public synchronized void onFailure() {
        failures++;
        if (failures >= policy.maxFailures()) {
            tripped = true;
        }
    }

    public synchronized boolean tripped() {
        return tripped;
    }

    public synchronized int failures() {
        return failures;
    }
}
