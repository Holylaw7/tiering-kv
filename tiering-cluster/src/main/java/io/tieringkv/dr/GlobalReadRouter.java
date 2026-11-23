package io.tieringkv.dr;

import java.util.Map;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** 全球读路由（ADR-0123）：就近读 + 一致性水位校验。 */
public final class GlobalReadRouter {

    private final Map<String, Long> replicatedSeq;
    private final Function<String, Long> localSeq;
    private final ConsistencyMode mode;
    private boolean providerMode;
    private long stalenessMillis;
    private final java.util.List<Long> stalenessSamples =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public GlobalReadRouter(Map<String, Long> replicatedSeq,
                            Function<String, Long> localSeq,
                            ConsistencyMode mode) {
        this.replicatedSeq = Map.copyOf(replicatedSeq);
        this.localSeq = localSeq;
        this.mode = mode;
        this.providerMode = false;
    }

    /** 水位提供者构造（ADR-0129）：复制管道/CRDT 已应用水位。 */
    public GlobalReadRouter(Supplier<Long> replicatedSeqProvider,
                            Function<String, Long> localSeq,
                            ConsistencyMode mode) {
        this(Map.of(), localSeq, mode);
        this.replicatedSeqProvider = replicatedSeqProvider;
        this.providerMode = true;
    }

    private Supplier<Long> replicatedSeqProvider = () -> 0L;

    public String route(String preferred, long requiredSeq) {
        long local = localSeq.apply(preferred);
        if (mode == ConsistencyMode.STRONG) {
            return local >= requiredSeq ? preferred : null;
        }
        long replicated = providerMode
                ? replicatedSeqProvider.get()
                : replicatedSeq.getOrDefault(preferred, 0L);
        if (local >= requiredSeq) {
            return preferred;
        }
        if (replicated >= requiredSeq) {
            stalenessMillis = 0;
            return preferred;
        }
        return null;
    }

    public void recordStaleness(long millis) {
        stalenessMillis = millis;
        stalenessSamples.add(millis);
    }

    public long stalenessMillis() {
        return stalenessMillis;
    }

    /** 陈旧度分位（ADR-0129）：p50/p95/p99。 */
    public long[] stalenessPercentiles() {
        if (stalenessSamples.isEmpty()) {
            return new long[]{0, 0, 0};
        }
        List<Long> sorted = new java.util.ArrayList<>(stalenessSamples);
        sorted.sort(Long::compareTo);
        return new long[]{
                sorted.get(sorted.size() / 2),
                sorted.get((int) (sorted.size() * 0.95) - 1),
                sorted.get(sorted.size() - 1)
        };
    }
}
