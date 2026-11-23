package io.tieringkv.cluster.region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Region 管理器（ADR-0057）：create / split / merge / route lookup /
 * epoch guard。线程安全；键范围按 startKey 有序，路由二分定位。
 */
public final class RegionManager {

    private final TreeMap<byte[], Region> regions = new TreeMap<>(
            Arrays::compareUnsigned);
    private final Map<RegionId, Region> byId = new ConcurrentHashMap<>();
    private final Map<RegionId, Long> sizes = new ConcurrentHashMap<>();

    public synchronized Region createRegion(RegionId regionId,
                                            byte[] startKey,
                                            byte[] endKey,
                                            List<String> peers,
                                            RegionEpoch epoch,
                                            String leader) {
        if (byId.containsKey(regionId)) {
            throw new IllegalArgumentException("region already exists: " + regionId);
        }
        Region region = new Region(regionId, startKey, endKey,
                leader, peers, epoch, RegionState.NORMAL);
        regions.put(region.startKey(), region);
        byId.put(regionId, region);
        return region;
    }

    public synchronized Region get(RegionId regionId) {
        return byId.get(regionId);
    }

    /** 生命周期状态标记（ADR-0061/0062）：epoch 不变，仅供控制器编排。 */
    public synchronized Region markState(RegionId regionId, RegionState state) {
        Region region = byId.get(regionId);
        if (region == null) {
            throw new IllegalArgumentException("unknown region " + regionId);
        }
        Region updated = region.withState(state);
        regions.put(updated.startKey(), updated);
        byId.put(regionId, updated);
        return updated;
    }

    /** 路由：返回包含 key 的 NORMAL region；不存在抛 IllegalStateException。 */
    public synchronized Region route(byte[] key) {
        Map.Entry<byte[], Region> floor = regions.floorEntry(key);
        if (floor == null) {
            throw new IllegalStateException("no region covers key");
        }
        Region region = floor.getValue();
        if (!region.contains(key) || region.state() == RegionState.TOMBSTONE) {
            throw new IllegalStateException("no region covers key");
        }
        return region;
    }

    /** 严格路由：纪元不匹配时抛 StaleRegionEpochException（旧路由写入保护）。 */
    public synchronized Region routeStrict(byte[] key, RegionEpoch expectedEpoch) {
        Region region = route(key);
        guardEpoch(region, expectedEpoch);
        return region;
    }

    public synchronized boolean guardEpoch(RegionId regionId, RegionEpoch expectedEpoch) {
        Region region = byId.get(regionId);
        if (region == null) {
            throw new IllegalArgumentException("unknown region " + regionId);
        }
        if (region.state() == RegionState.TOMBSTONE) {
            // tombstone 区域已失效：任何携带其纪元的请求都是旧请求
            return false;
        }
        return !expectedEpoch.olderThan(region.epoch());
    }

    private static void guardEpoch(Region region, RegionEpoch expectedEpoch) {
        if (expectedEpoch.olderThan(region.epoch())) {
            throw new StaleRegionEpochException(
                    region.regionId(), expectedEpoch, region.epoch());
        }
    }

    /** 分裂：parent → TOMBSTONE，两个子 region 继承范围并推进 confVer。 */
    public synchronized List<Region> splitRegion(RegionId regionId, byte[] splitKey) {
        Region parent = requireLifecycle(regionId, RegionState.SPLITTING);
        if (parent.endKey() == null) {
            throw new IllegalStateException("cannot split open-ended region");
        }
        int cmp = Arrays.compareUnsigned(splitKey, parent.startKey());
        if (cmp <= 0 || Arrays.compareUnsigned(splitKey, parent.endKey()) >= 0) {
            throw new IllegalArgumentException("split key outside region range");
        }
        RegionEpoch childEpoch = parent.epoch().advanceConfVer();
        RegionId leftId = new RegionId(regionId.id() * 10 + 1);
        RegionId rightId = new RegionId(regionId.id() * 10 + 2);
        Region left = new Region(leftId, parent.startKey(), splitKey,
                parent.leader(), parent.peers(), childEpoch, RegionState.NORMAL);
        Region right = new Region(rightId, splitKey, parent.endKey(),
                parent.leader(), parent.peers(), childEpoch, RegionState.NORMAL);
        // 注意：tombstone 不进入路由表（父与左子 startKey 相同会冲突）
        regions.remove(parent.startKey());
        regions.put(left.startKey(), left);
        regions.put(right.startKey(), right);
        byId.put(leftId, left);
        byId.put(rightId, right);
        byId.put(regionId, parent.withState(RegionState.TOMBSTONE));
        return List.of(left, right);
    }

    /** 合并：两个相邻 region → TOMBSTONE，合并 region 推进 confVer + version。 */
    public synchronized Region mergeRegion(RegionId leftId, RegionId rightId) {
        Region left = requireLifecycle(leftId, RegionState.MERGING);
        Region right = requireLifecycle(rightId, RegionState.MERGING);
        if (!Arrays.equals(left.endKey(), right.startKey())) {
            throw new IllegalArgumentException("regions are not adjacent");
        }
        RegionEpoch mergedEpoch = left.epoch()
                .advanceConfVer().advanceVersion();
        List<String> peers = new ArrayList<>(left.peers());
        for (String peer : right.peers()) {
            if (!peers.contains(peer)) {
                peers.add(peer);
            }
        }
        RegionId mergedId = new RegionId(leftId.id() * 10 + 3);
        Region merged = new Region(mergedId, left.startKey(), right.endKey(),
                left.leader(), peers, mergedEpoch, RegionState.NORMAL);
        regions.remove(left.startKey());
        regions.remove(right.startKey());
        regions.put(merged.startKey(), merged);
        byId.put(mergedId, merged);
        byId.put(leftId, left.withState(RegionState.TOMBSTONE));
        byId.put(rightId, right.withState(RegionState.TOMBSTONE));
        return merged;
    }

    /** leader 转移（ADR-0060）：校验新 leader ∈ peers，epoch confVer 推进。 */
    public synchronized Region transferLeader(RegionId regionId, String newLeader) {
        Region region = requireNormal(regionId);
        if (newLeader == null || !region.peers().contains(newLeader)) {
            throw new IllegalArgumentException(
                    "new leader must be a region peer: " + newLeader);
        }
        Region updated = region.withLeader(newLeader);
        regions.put(updated.startKey(), updated);
        byId.put(regionId, updated);
        return updated;
    }

    private Region requireNormal(RegionId regionId) {
        return requireLifecycle(regionId, null);
    }

    /** 要求 region 存在且处于 NORMAL 或指定生命周期状态。 */
    private Region requireLifecycle(RegionId regionId, RegionState lifecycleState) {
        Region region = byId.get(regionId);
        if (region == null) {
            throw new IllegalArgumentException("unknown region " + regionId);
        }
        boolean allowed = region.state() == RegionState.NORMAL
                || (lifecycleState != null
                && region.state() == lifecycleState);
        if (!allowed) {
            throw new IllegalStateException(
                    "region not normal: " + regionId + " state=" + region.state());
        }
        return region;
    }

    public synchronized List<Region> listRegions() {
        return List.copyOf(byId.values());
    }

    public synchronized int regionCount() {
        return (int) byId.values().stream()
                .filter(r -> r.state() != RegionState.TOMBSTONE).count();
    }

    public synchronized int splitCount() {
        return (int) byId.values().stream()
                .filter(r -> r.state() == RegionState.TOMBSTONE).count();
    }

    public void setRegionSize(RegionId regionId, long bytes) {
        sizes.put(regionId, Math.max(0, bytes));
    }

    public long regionSize(RegionId regionId) {
        return sizes.getOrDefault(regionId, 0L);
    }

    public long totalSize() {
        return sizes.values().stream().mapToLong(Long::longValue).sum();
    }
}
