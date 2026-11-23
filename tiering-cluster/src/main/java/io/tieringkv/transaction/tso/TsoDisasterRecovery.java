package io.tieringkv.transaction.tso;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TSO 跨地域容灾（ADR-0223）：主备双实例，水位定期同步；
 * 切换后备用节点以已同步水位继续分配，单调不回退。
 */
public final class TsoDisasterRecovery {

    /** 容灾状态。 */
    public enum State {
        PRIMARY_ACTIVE,
        STANDBY_ACTIVE,
        FAILOVER_IN_PROGRESS
    }

    private final TsoService primary;
    private final TsoService standby;
    private final AtomicLong syncedWatermark = new AtomicLong(-1);
    private final AtomicBoolean primaryActive = new AtomicBoolean(true);
    private volatile State state = State.PRIMARY_ACTIVE;
    private long failoverCount;

    public TsoDisasterRecovery() {
        this(new TsoService(), new TsoService());
    }

    public TsoDisasterRecovery(TsoService primary,
                               TsoService standby) {
        if (primary == null || standby == null) {
            throw new IllegalArgumentException(
                    "primary and standby required");
        }
        this.primary = primary;
        this.standby = standby;
    }

    /** 主实例批量分配，并同步水位到备实例。 */
    public long[] allocate(int batchSize) {
        if (!primaryActive.get()) {
            return standby.allocate(batchSize);
        }
        long[] range = primary.allocate(batchSize);
        syncedWatermark.accumulateAndGet(range[1], Math::max);
        return range;
    }

    /** 单分配。 */
    public long allocate() {
        return allocate(1)[0];
    }

    /** 故障切换：备实例以已同步水位恢复并接管（单调不回退）。 */
    public synchronized long failover() {
        state = State.FAILOVER_IN_PROGRESS;
        long watermark = Math.max(0, syncedWatermark.get());
        long restored = standby.restore(watermark);
        primaryActive.set(false);
        state = State.STANDBY_ACTIVE;
        failoverCount++;
        return restored;
    }

    /** 原主恢复：以备实例水位恢复，重新接管。 */
    public synchronized long recoverPrimary() {
        if (primaryActive.get()) {
            return primary.watermark();
        }
        long restored = primary.restore(standby.watermark());
        primaryActive.set(true);
        state = State.PRIMARY_ACTIVE;
        return restored;
    }

    public State state() {
        return state;
    }

    public long syncedWatermark() {
        return syncedWatermark.get();
    }

    public long primaryWatermark() {
        return primary.watermark();
    }

    public long standbyWatermark() {
        return standby.watermark();
    }

    public long failoverCount() {
        return failoverCount;
    }

    public boolean primaryActive() {
        return primaryActive.get();
    }
}
