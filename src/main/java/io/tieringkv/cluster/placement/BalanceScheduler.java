package io.tieringkv.cluster.placement;

import io.tieringkv.cluster.region.Region;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;
import io.tieringkv.cluster.region.RegionState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动均衡调度器（ADR-0065）：检测 region 数 / leader / 磁盘 / CPU 压力，
 * 生成 BalancePlan；不自动执行危险迁移（数据搬迁需人工触发，
 * leader 转移经注入执行器完成）。
 */
public final class BalanceScheduler {

    @FunctionalInterface
    public interface LeaderTransferExecutor {
        boolean transfer(RegionId regionId, String targetNode);
    }

    private final RegionManager regions;
    private final int maxSkew;
    private final Map<String, Long> diskBytes;
    private final long diskThreshold;
    private final Map<String, Integer> cpuLoad;
    private final int cpuThreshold;
    private final int planLimit;

    public BalanceScheduler(RegionManager regions, int maxSkew,
                            Map<String, Long> diskBytes, long diskThreshold,
                            Map<String, Integer> cpuLoad, int cpuThreshold,
                            int planLimit) {
        this.regions = regions;
        this.maxSkew = maxSkew;
        this.diskBytes = Map.copyOf(diskBytes);
        this.diskThreshold = diskThreshold;
        this.cpuLoad = Map.copyOf(cpuLoad);
        this.cpuThreshold = cpuThreshold;
        this.planLimit = Math.max(1, planLimit);
    }

    public BalancePlan evaluate() {
        List<RegionMove> moves = new ArrayList<>();
        Set<RegionId> planned = new HashSet<>();
        List<Region> active = regions.listRegions().stream()
                .filter(r -> r.state() == RegionState.NORMAL).toList();
        Map<String, Integer> regionCounts = counts(active, false);
        Map<String, Integer> leaderCounts = counts(active, true);
        int regionSkew = skew(regionCounts);
        int leaderSkew = skew(leaderCounts);

        // 1) leader 均衡：从 leader 数多的节点转移到 leader 数少的 peer
        for (Region region : active) {
            if (moves.size() >= planLimit) {
                break;
            }
            String from = region.leader();
            String to = underloadedPeer(region, leaderCounts);
            if (to != null && leaderCounts.getOrDefault(from, 0)
                    - leaderCounts.getOrDefault(to, 0) > maxSkew) {
                moves.add(new RegionMove(region.regionId(), from, to,
                        "leader_balance", true, region.epoch()));
                planned.add(region.regionId());
                leaderCounts.merge(from, -1, Integer::sum);
                leaderCounts.merge(to, 1, Integer::sum);
            }
        }
        // 2) region 数均衡
        for (Region region : active) {
            if (moves.size() >= planLimit || planned.contains(region.regionId())) {
                continue;
            }
            String from = maxPeer(region, regionCounts);
            String to = minPeer(region, regionCounts);
            if (from != null && to != null && !from.equals(to)
                    && regionCounts.getOrDefault(from, 0)
                    - regionCounts.getOrDefault(to, 0) > maxSkew) {
                moves.add(new RegionMove(region.regionId(), from, to,
                        "region_count", false, region.epoch()));
                planned.add(region.regionId());
                regionCounts.merge(from, -1, Integer::sum);
                regionCounts.merge(to, 1, Integer::sum);
            }
        }
        // 3) 磁盘压力
        for (Region region : active) {
            if (moves.size() >= planLimit || planned.contains(region.regionId())) {
                continue;
            }
            String from = maxDiskPeer(region);
            String to = minDiskPeer(region);
            if (from != null && to != null && !from.equals(to)
                    && diskBytes.getOrDefault(from, 0L) > diskThreshold) {
                moves.add(new RegionMove(region.regionId(), from, to,
                        "disk_pressure", false, region.epoch()));
                planned.add(region.regionId());
            }
        }
        // 4) CPU 压力
        for (Region region : active) {
            if (moves.size() >= planLimit || planned.contains(region.regionId())) {
                continue;
            }
            String from = maxCpuPeer(region);
            String to = minCpuPeer(region);
            if (from != null && to != null && !from.equals(to)
                    && cpuLoad.getOrDefault(from, 0) > cpuThreshold) {
                moves.add(new RegionMove(region.regionId(), from, to,
                        "cpu_pressure", false, region.epoch()));
                planned.add(region.regionId());
            }
        }
        boolean balanced = regionSkew <= maxSkew && leaderSkew <= maxSkew;
        return new BalancePlan(moves, balanced, regionSkew, leaderSkew);
    }

    /** 执行 leader 转移（epoch 校验；数据搬迁不自动执行）。 */
    public int executeLeaderMoves(BalancePlan plan,
                                  LeaderTransferExecutor executor) {
        int executed = 0;
        for (RegionMove move : plan.moves()) {
            if (!move.leaderMove()) {
                continue;
            }
            Region current = regions.get(move.regionId());
            if (current == null || current.state() != RegionState.NORMAL
                    || move.epoch().olderThan(current.epoch())) {
                continue; // 纪元已过期，跳过
            }
            if (executor.transfer(move.regionId(), move.toNode())) {
                executed++;
            }
        }
        return executed;
    }

    private String underloadedPeer(Region region, Map<String, Integer> counts) {
        for (String peer : region.peers()) {
            if (!peer.equals(region.leader())
                    && counts.getOrDefault(peer, 0)
                    < counts.getOrDefault(region.leader(), 0)) {
                return peer;
            }
        }
        return null;
    }

    private String maxPeer(Region region, Map<String, Integer> counts) {
        String max = null;
        int maxCount = Integer.MIN_VALUE;
        for (String peer : region.peers()) {
            int count = counts.getOrDefault(peer, 0);
            if (count > maxCount || (count == maxCount && peer.equals(region.leader()))) {
                max = peer;
                maxCount = count;
            }
        }
        return max;
    }

    private String minPeer(Region region, Map<String, Integer> counts) {
        String min = null;
        int minCount = Integer.MAX_VALUE;
        for (String peer : region.peers()) {
            int count = counts.getOrDefault(peer, 0);
            if (count < minCount) {
                min = peer;
                minCount = count;
            }
        }
        return min;
    }

    private String maxDiskPeer(Region region) {
        String max = null;
        long maxBytes = -1;
        for (String peer : region.peers()) {
            long bytes = diskBytes.getOrDefault(peer, 0L);
            if (bytes > maxBytes) {
                max = peer;
                maxBytes = bytes;
            }
        }
        return max;
    }

    private String minDiskPeer(Region region) {
        String min = null;
        long minBytes = Long.MAX_VALUE;
        for (String peer : region.peers()) {
            long bytes = diskBytes.getOrDefault(peer, 0L);
            if (bytes < minBytes) {
                min = peer;
                minBytes = bytes;
            }
        }
        return min;
    }

    private String maxCpuPeer(Region region) {
        String max = null;
        int maxCpu = -1;
        for (String peer : region.peers()) {
            int cpu = cpuLoad.getOrDefault(peer, 0);
            if (cpu > maxCpu) {
                max = peer;
                maxCpu = cpu;
            }
        }
        return max;
    }

    private String minCpuPeer(Region region) {
        String min = null;
        int minCpu = Integer.MAX_VALUE;
        for (String peer : region.peers()) {
            int cpu = cpuLoad.getOrDefault(peer, 0);
            if (cpu < minCpu) {
                min = peer;
                minCpu = cpu;
            }
        }
        return min;
    }

    private static Map<String, Integer> counts(List<Region> regions,
                                               boolean leadersOnly) {
        Map<String, Integer> counts = new HashMap<>();
        for (Region region : regions) {
            String key = leadersOnly ? region.leader() : null;
            if (leadersOnly) {
                counts.merge(key, 1, Integer::sum);
            } else {
                for (String peer : region.peers()) {
                    counts.merge(peer, 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private static int skew(Map<String, Integer> counts) {
        if (counts.isEmpty()) {
            return 0;
        }
        int min = counts.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return max - min;
    }
}
