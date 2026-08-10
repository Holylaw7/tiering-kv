package io.tieringkv.cluster.region;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Region 指标（ADR-0056/0060 扩展）：region_count / region_size /
 * region_split_count / raft_group_count / leader_distribution /
 * region_move_bytes。
 */
public final class RegionMetricsRegistry {

    private final LongAdder splits = new LongAdder();
    private final LongAdder moveBytes = new LongAdder();
    private final AtomicLong regionCount = new AtomicLong();
    private final AtomicLong regionSize = new AtomicLong();
    private final AtomicLong raftGroupCount = new AtomicLong();
    private volatile String leaderDistribution = "";

    public void recordSplit() {
        splits.increment();
    }

    public void recordRegionMoveBytes(long bytes) {
        moveBytes.add(Math.max(0, bytes));
    }

    public void setRegionCount(long count) {
        regionCount.set(Math.max(0, count));
    }

    public void setRegionSize(long bytes) {
        regionSize.set(Math.max(0, bytes));
    }

    public void setRaftGroupCount(long count) {
        raftGroupCount.set(Math.max(0, count));
    }

    public void setLeaderDistribution(String distribution) {
        this.leaderDistribution = distribution == null ? "" : distribution;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                regionCount.get(),
                regionSize.get(),
                splits.sum(),
                raftGroupCount.get(),
                leaderDistribution,
                moveBytes.sum());
    }

    public String metricLines() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "region_count:%d\r\n"
                        + "region_size_bytes:%d\r\n"
                        + "region_split_count:%d\r\n"
                        + "raft_group_count:%d\r\n"
                        + "leader_distribution:%s\r\n"
                        + "region_move_bytes:%d\r\n",
                s.regionCount(),
                s.regionSizeBytes(),
                s.regionSplitCount(),
                s.raftGroupCount(),
                s.leaderDistribution(),
                s.regionMoveBytes());
    }

    public String sectionText() {
        return "# Regions\r\n" + metricLines();
    }

    public record Snapshot(
            long regionCount,
            long regionSizeBytes,
            long regionSplitCount,
            long raftGroupCount,
            String leaderDistribution,
            long regionMoveBytes) {
    }
}
