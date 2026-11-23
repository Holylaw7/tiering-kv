package io.tieringkv.transaction.async;

import java.util.concurrent.atomic.AtomicLong;

/** Resolved Timestamp（ADR-0209）：跨区一致性读水位。 */
public final class ResolvedTimestampService {

    private final AtomicLong resolvedTs = new AtomicLong();

    /** 推进水位：仅允许单调递增。 */
    public long advance(long candidateTs) {
        while (true) {
            long current = resolvedTs.get();
            if (candidateTs <= current) {
                return current;
            }
            if (resolvedTs.compareAndSet(current, candidateTs)) {
                return candidateTs;
            }
        }
    }

    public long resolvedTs() {
        return resolvedTs.get();
    }
}
