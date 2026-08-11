package io.tieringkv.dr;

import java.util.Map;
import java.util.function.Function;

/** 全球读路由（ADR-0123）：就近读 + 一致性水位校验。 */
public final class GlobalReadRouter {

    private final Map<String, Long> replicatedSeq;
    private final Function<String, Long> localSeq;
    private final ConsistencyMode mode;
    private long stalenessMillis;

    public GlobalReadRouter(Map<String, Long> replicatedSeq,
                            Function<String, Long> localSeq,
                            ConsistencyMode mode) {
        this.replicatedSeq = Map.copyOf(replicatedSeq);
        this.localSeq = localSeq;
        this.mode = mode;
    }

    public String route(String preferred, long requiredSeq) {
        long local = localSeq.apply(preferred);
        if (mode == ConsistencyMode.STRONG) {
            return local >= requiredSeq ? preferred : null;
        }
        long replicated = replicatedSeq.getOrDefault(preferred, 0L);
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
    }

    public long stalenessMillis() {
        return stalenessMillis;
    }
}
