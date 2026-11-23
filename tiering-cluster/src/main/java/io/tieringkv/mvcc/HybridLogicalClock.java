package io.tieringkv.mvcc;

import java.util.concurrent.atomic.AtomicLong;

/** HLC（ADR-0072）：physicalMillis*1e6 + logical；回拨不倒退。 */
public final class HybridLogicalClock {

    private static final long LOGICAL_MAX = 1_000_000L;

    private final AtomicLong physical = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong logical = new AtomicLong();

    public synchronized long now() {
        long wall = System.currentTimeMillis();
        long p = physical.get();
        long l = logical.get();
        if (wall > p) {
            physical.set(wall);
            logical.set(0);
            return wall * LOGICAL_MAX;
        }
        if (l + 1 >= LOGICAL_MAX) {
            physical.incrementAndGet();
            logical.set(0);
            return physical.get() * LOGICAL_MAX;
        }
        logical.incrementAndGet();
        return p * LOGICAL_MAX + logical.get();
    }

    /** HLC 合并：采纳远端时间，本地不倒退。 */
    public synchronized void update(long remote) {
        long remotePhysical = remote / LOGICAL_MAX;
        long remoteLogical = remote % LOGICAL_MAX;
        long p = physical.get();
        long l = logical.get();
        if (remotePhysical > p) {
            physical.set(remotePhysical);
            logical.set(remoteLogical + 1);
        } else if (remotePhysical == p) {
            logical.set(Math.max(l, remoteLogical) + 1);
        }
    }

    public long physicalTime() {
        return physical.get();
    }

    public long logicalCounter() {
        return logical.get();
    }
}
