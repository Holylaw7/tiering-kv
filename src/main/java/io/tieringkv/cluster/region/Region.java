package io.tieringkv.cluster.region;

import java.util.Arrays;
import java.util.List;

/**
 * Region（ADR-0057）：键范围 [startKey, endKey) + leader/peers +
 * epoch + 状态。startKey 包含、endKey 不包含（null 表示 +∞）。
 */
public record Region(
        RegionId regionId,
        byte[] startKey,
        byte[] endKey,
        String leader,
        List<String> peers,
        RegionEpoch epoch,
        RegionState state) {

    public Region {
        startKey = startKey == null ? new byte[0] : startKey.clone();
        endKey = endKey == null ? null : endKey.clone();
        peers = List.copyOf(peers);
        if (endKey != null && Arrays.compareUnsigned(startKey, endKey) >= 0) {
            throw new IllegalArgumentException(
                    "startKey must be less than endKey");
        }
    }

    @Override
    public byte[] startKey() {
        return startKey.clone();
    }

    @Override
    public byte[] endKey() {
        return endKey == null ? null : endKey.clone();
    }

    public boolean contains(byte[] key) {
        if (Arrays.compareUnsigned(key, startKey) < 0) {
            return false;
        }
        return endKey == null || Arrays.compareUnsigned(key, endKey) < 0;
    }

    public Region withLeader(String newLeader) {
        return new Region(regionId, startKey, endKey, newLeader, peers,
                epoch.advanceConfVer(), state);
    }

    public Region withState(RegionState newState) {
        return new Region(regionId, startKey, endKey, leader, peers, epoch, newState);
    }

    public Region withEpoch(RegionEpoch newEpoch) {
        return new Region(regionId, startKey, endKey, leader, peers, newEpoch, state);
    }

    @Override
    public String toString() {
        return "Region(" + regionId + ", [" + Arrays.toString(startKey)
                + ", " + Arrays.toString(endKey) + "), leader=" + leader
                + ", epoch=" + epoch + ", " + state + ")";
    }
}
