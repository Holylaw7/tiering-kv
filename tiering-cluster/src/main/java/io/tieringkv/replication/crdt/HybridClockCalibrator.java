package io.tieringkv.replication.crdt;

import java.util.List;

/** 时钟校准（ADR-0122）：估计节点间偏差并输出偏移。 */
public final class HybridClockCalibrator {

    public record Sample(long localMillis, long remoteMillis) {
    }

    public long estimateOffset(List<Sample> samples) {
        if (samples.isEmpty()) {
            return 0;
        }
        return samples.stream()
                .mapToLong(sample -> sample.remoteMillis()
                        - sample.localMillis())
                .sum() / samples.size();
    }

    /** 按偏移调整远程时间戳后比较（LWW 校准）。 */
    public long adjust(long remoteTimestamp, long offset) {
        return remoteTimestamp - offset;
    }
}
