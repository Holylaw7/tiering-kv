package io.tieringkv.cluster.placement;

import io.tieringkv.cluster.region.Region;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;
import io.tieringkv.cluster.region.RegionState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 放置控制原型（ADR-0060）：region 分布统计 / 均衡检查 / leader 转移。
 * 暂不实现自动 rebalance（明确记录）。
 */
public final class PlacementManager {

    private final RegionManager regions;

    public PlacementManager(RegionManager regions) {
        this.regions = regions;
    }

    /** 每节点承载的 NORMAL region 列表（含 leader 所在节点）。 */
    public Map<String, List<RegionId>> distribution() {
        Map<String, List<RegionId>> result = new HashMap<>();
        for (Region region : regions.listRegions()) {
            if (region.state() != RegionState.NORMAL) {
                continue;
            }
            for (String peer : region.peers()) {
                result.computeIfAbsent(peer, ignored -> new ArrayList<>())
                        .add(region.regionId());
            }
        }
        return result;
    }

    public int maxRegionsPerNode() {
        return distribution().values().stream()
                .mapToInt(List::size).max().orElse(0);
    }

    public int minRegionsPerNode() {
        return distribution().values().stream()
                .mapToInt(List::size).min().orElse(0);
    }

    /** 最大-最小区域数差异（均衡度）。 */
    public int balanceSkew() {
        return maxRegionsPerNode() - minRegionsPerNode();
    }

    /** 均衡检查：skew &lt;= threshold 视为均衡。 */
    public boolean isBalanced(int maxSkew) {
        return balanceSkew() <= maxSkew;
    }

    /** leader 转移：校验 + 推进 epoch（ADR-0060）。 */
    public Region transferLeader(RegionId regionId, String newLeader) {
        return regions.transferLeader(regionId, newLeader);
    }

    public RegionManager regions() {
        return regions;
    }
}
