package io.tieringkv.cluster.lifecycle;

import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.region.Region;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Leader 交接管理器（ADR-0064）：真实 Raft 交接 + 元数据 epoch 同步。
 * 校验 target ∈ peers 且日志追平；交接成功后更新 Region leader。
 */
public final class LeaderTransferManager {

    private final RegionManager regions;
    private final Map<RegionId, RaftNode> raftByRegion;

    public LeaderTransferManager(RegionManager regions,
                                 Map<RegionId, RaftNode> raftByRegion) {
        this.regions = regions;
        this.raftByRegion = Map.copyOf(raftByRegion);
    }

    /** 交接并更新元数据；返回是否成功。 */
    public boolean transferLeader(RegionId regionId, String targetNode) {
        RaftNode raft = raftByRegion.get(regionId);
        Region region = regions.get(regionId);
        if (raft == null || region == null) {
            throw new IllegalArgumentException("unknown region " + regionId);
        }
        if (targetNode == null || !region.peers().contains(targetNode)) {
            throw new IllegalArgumentException("target must be a region peer");
        }
        try {
            Boolean accepted = raft.transferLeadership(targetNode)
                    .get(5, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(accepted)) {
                regions.transferLeader(regionId, targetNode);
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
