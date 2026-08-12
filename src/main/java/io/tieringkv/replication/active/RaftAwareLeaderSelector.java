package io.tieringkv.replication.active;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 选主与 Raft term 联动（ADR-0145）：term 单调 + 健康探测 + 自动选主；
 * 低 term 地域不得自封 leader（防脑裂）。
 */
public final class RaftAwareLeaderSelector {

    /** 地域 Raft 状态：term + 健康。 */
    public record RegionState(long term, boolean healthy) {
    }

    private final Map<String, RegionState> regions =
            new LinkedHashMap<>();
    private volatile String leader;
    private volatile long currentTerm;

    public RaftAwareLeaderSelector(Map<String, RegionState> regions,
                                   String initialLeader) {
        this.regions.putAll(regions);
        this.leader = initialLeader;
        this.currentTerm = regions.values().stream()
                .mapToLong(RegionState::term).max().orElse(0);
    }

    /** 自动选主：优先保留健康且 term 等于当前最大 term 的 leader。 */
    public synchronized String selectLeader() {
        RegionState current = regions.get(leader);
        if (current != null && current.healthy()
                && current.term() == currentTerm) {
            return leader;
        }
        long maxTerm = regions.values().stream()
                .mapToLong(RegionState::term).max().orElse(0);
        currentTerm = Math.max(currentTerm, maxTerm);
        for (Map.Entry<String, RegionState> entry
                : regions.entrySet()) {
            RegionState candidate = entry.getValue();
            if (candidate.healthy() && candidate.term() == currentTerm) {
                leader = entry.getKey();
                return leader;
            }
        }
        return null;
    }

    /** 候选地域尝试自封 leader；低 term 拒绝（防脑裂）。 */
    public synchronized boolean tryBecomeLeader(String region,
                                                long term) {
        if (term < currentTerm) {
            return false;
        }
        RegionState candidate = regions.get(region);
        if (candidate == null || !candidate.healthy()) {
            return false;
        }
        currentTerm = Math.max(currentTerm, term);
        if (term == currentTerm) {
            leader = region;
            return true;
        }
        return false;
    }

    /** 更新地域 Raft 状态；term 只增不减。 */
    public synchronized void updateRegion(String region, long term,
                                          boolean healthy) {
        RegionState state = regions.get(region);
        long merged = state == null ? term
                : Math.max(state.term(), term);
        regions.put(region, new RegionState(merged, healthy));
        currentTerm = Math.max(currentTerm, merged);
    }

    public boolean majorityHealthy() {
        long healthy = regions.values().stream()
                .filter(RegionState::healthy).count();
        return healthy * 2 > regions.size();
    }

    public String leader() {
        return leader;
    }

    public long currentTerm() {
        return currentTerm;
    }
}
