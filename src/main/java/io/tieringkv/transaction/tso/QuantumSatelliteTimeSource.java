package io.tieringkv.transaction.tso;

/**
 * 量子/卫星授时源原型（ADR-0244）：授时类型 + 传播延迟校正 +
 * 单调推进 + 防回拨。
 */
public final class QuantumSatelliteTimeSource {

    public enum SourceKind {
        QUANTUM,
        SATELLITE,
        HYBRID
    }

    private final SourceKind kind;
    private final long propagationDelayMillis;
    private long lastTimestamp = Long.MIN_VALUE;
    private long correctedReadings;

    public QuantumSatelliteTimeSource(SourceKind kind,
                                      long propagationDelayMillis) {
        if (kind == null || propagationDelayMillis < 0) {
            throw new IllegalArgumentException(
                    "kind required and delay must be non-negative");
        }
        this.kind = kind;
        this.propagationDelayMillis = propagationDelayMillis;
    }

    /** 校正：源时间 + 传播延迟（模拟真实授时链路）。 */
    public long corrected(long sourceTimeMillis) {
        correctedReadings++;
        return sourceTimeMillis + propagationDelayMillis;
    }

    /** 单调推进：max(校正时间, 上次 + 1)，绝不回拨。 */
    public synchronized long timestamp(long sourceTimeMillis) {
        long corrected = corrected(sourceTimeMillis);
        long candidate = lastTimestamp == Long.MIN_VALUE
                ? corrected
                : Math.max(corrected, lastTimestamp + 1);
        if (candidate <= lastTimestamp) {
            return lastTimestamp;
        }
        lastTimestamp = candidate;
        return candidate;
    }

    public long restore(long persistedWatermark) {
        if (persistedWatermark < 0) {
            throw new IllegalArgumentException(
                    "watermark must be non-negative");
        }
        lastTimestamp = Math.max(lastTimestamp,
                persistedWatermark);
        return persistedWatermark;
    }

    public SourceKind kind() {
        return kind;
    }

    public long propagationDelayMillis() {
        return propagationDelayMillis;
    }

    public long correctedReadings() {
        return correctedReadings;
    }
}
