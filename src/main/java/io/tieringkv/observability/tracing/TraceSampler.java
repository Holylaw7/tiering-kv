package io.tieringkv.observability.tracing;

/** 追踪采样（ADR-0154）：按 traceId 确定性采样。 */
public final class TraceSampler {

    private final double rate;

    public TraceSampler(double rate) {
        if (rate < 0 || rate > 1) {
            throw new IllegalArgumentException(
                    "rate must be in [0,1]");
        }
        this.rate = rate;
    }

    /** 同一 traceId 采样结果确定。 */
    public boolean sample(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return false;
        }
        if (rate >= 1.0) {
            return true;
        }
        if (rate <= 0) {
            return false;
        }
        int hash = traceId.hashCode() & 0x7fffffff;
        return (hash % 1000) / 1000.0 < rate;
    }
}
