package io.tieringkv.cluster.rpc;

import java.util.concurrent.atomic.AtomicLong;

/** RPC 请求关联 ID（ADR-0041）：单调递增，用于响应匹配。 */
public record RequestId(long value) {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    public static RequestId next() {
        return new RequestId(SEQUENCE.incrementAndGet());
    }
}
