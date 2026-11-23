package io.tieringkv.cluster.region;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Region 管理器（ADR-0057）：create/split/merge/route/epoch guard。 */
class RegionManagerTest {

    private RegionManager manager;

    @BeforeEach
    void setUp() {
        manager = new RegionManager();
    }

    @Test
    void createAndRoute() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("m"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.createRegion(new RegionId(2), bytes("m"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        assertThat(manager.route(bytes("b")).regionId()).isEqualTo(new RegionId(1));
        assertThat(manager.route(bytes("m")).regionId()).isEqualTo(new RegionId(2));
    }

    @Test
    void routeUsesFloorEntryOnStartKey() {
        manager.createRegion(new RegionId(1), bytes("b"), bytes("d"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.createRegion(new RegionId(2), bytes("d"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        assertThatThrownBy(() -> manager.route(bytes("a")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(manager.route(bytes("b")).regionId()).isEqualTo(new RegionId(1));
        assertThat(manager.route(bytes("c")).regionId()).isEqualTo(new RegionId(1));
        assertThat(manager.route(bytes("d")).regionId()).isEqualTo(new RegionId(2));
        assertThat(manager.route(bytes("y")).regionId()).isEqualTo(new RegionId(2));
        assertThatThrownBy(() -> manager.route(bytes("z")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownKeyThrows() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        assertThatThrownBy(() -> manager.route(bytes("0")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateCreateRejected() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        assertThatThrownBy(() -> manager.createRegion(
                new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitProducesTwoChildren() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        List<Region> children = manager.splitRegion(new RegionId(1), bytes("m"));
        assertThat(children).hasSize(2);
        assertThat(children.get(0).startKey()).isEqualTo(bytes("a"));
        assertThat(children.get(0).endKey()).isEqualTo(bytes("m"));
        assertThat(children.get(1).startKey()).isEqualTo(bytes("m"));
        assertThat(children.get(1).endKey()).isEqualTo(bytes("z"));
    }

    @Test
    void splitRangesStillCoverParentRange() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.splitRegion(new RegionId(1), bytes("m"));
        assertThat(manager.route(bytes("a")).regionId()).isNotEqualTo(new RegionId(1));
        assertThat(manager.route(bytes("m")).regionId()).isNotEqualTo(new RegionId(1));
        assertThat(manager.route(bytes("y")).regionId()).isNotEqualTo(new RegionId(1));
    }

    @Test
    void splitAdvancesEpoch() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        List<Region> children = manager.splitRegion(new RegionId(1), bytes("m"));
        assertThat(children.get(0).epoch().confVer()).isEqualTo(2);
        assertThat(children.get(1).epoch().confVer()).isEqualTo(2);
    }

    @Test
    void parentTombstonedAfterSplit() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.splitRegion(new RegionId(1), bytes("m"));
        assertThat(manager.get(new RegionId(1)).state())
                .isEqualTo(RegionState.TOMBSTONE);
    }

    @Test
    void splitInvalidKeyRejected() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        assertThatThrownBy(() -> manager.splitRegion(new RegionId(1), bytes("a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.splitRegion(new RegionId(1), bytes("z")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.splitRegion(new RegionId(1), bytes("0")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitTombstoneNotSplittable() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.splitRegion(new RegionId(1), bytes("m"));
        assertThatThrownBy(() -> manager.splitRegion(new RegionId(1), bytes("q")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mergeAdjacentRegions() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("m"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.createRegion(new RegionId(2), bytes("m"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        Region merged = manager.mergeRegion(new RegionId(1), new RegionId(2));
        assertThat(merged.startKey()).isEqualTo(bytes("a"));
        assertThat(merged.endKey()).isEqualTo(bytes("z"));
        assertThat(merged.epoch().confVer()).isEqualTo(2);
        assertThat(merged.epoch().version()).isEqualTo(2);
    }

    @Test
    void mergeNonAdjacentRejected() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("m"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.createRegion(new RegionId(2), bytes("n"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        assertThatThrownBy(() -> manager.mergeRegion(new RegionId(1), new RegionId(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void staleEpochRouteRejected() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.splitRegion(new RegionId(1), bytes("m"));
        assertThatThrownBy(() -> manager.routeStrict(bytes("b"), RegionEpoch.INITIAL))
                .isInstanceOf(StaleRegionEpochException.class);
    }

    @Test
    void currentEpochRouteAccepted() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        Region routed = manager.routeStrict(bytes("b"), RegionEpoch.INITIAL);
        assertThat(routed.regionId()).isEqualTo(new RegionId(1));
    }

    @Test
    void guardEpochMatches() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        List<Region> children = manager.splitRegion(new RegionId(1), bytes("m"));
        RegionEpoch current = children.get(0).epoch();
        assertThat(manager.guardEpoch(children.get(0).regionId(), current)).isTrue();
        // 旧纪元（分裂前）必须被拒绝
        assertThat(manager.guardEpoch(children.get(0).regionId(),
                RegionEpoch.INITIAL)).isFalse();
    }

    @Test
    void guardUnknownRegionThrows() {
        assertThatThrownBy(() -> manager.guardEpoch(new RegionId(9), RegionEpoch.INITIAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void regionCountExcludesTombstones() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.splitRegion(new RegionId(1), bytes("m"));
        assertThat(manager.regionCount()).isEqualTo(2);
    }

    @Test
    void splitThenRouteWorks() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.splitRegion(new RegionId(1), bytes("m"));
        assertThat(manager.route(bytes("b")).regionId().id()).isEqualTo(11);
        assertThat(manager.route(bytes("y")).regionId().id()).isEqualTo(12);
    }

    @Test
    void mergeThenRouteWorks() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("m"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.createRegion(new RegionId(2), bytes("m"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.mergeRegion(new RegionId(1), new RegionId(2));
        assertThat(manager.route(bytes("b")).regionId().id()).isEqualTo(13);
        assertThat(manager.route(bytes("y")).regionId().id()).isEqualTo(13);
    }

    @Test
    void sizeTracking() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.setRegionSize(new RegionId(1), 1024);
        assertThat(manager.regionSize(new RegionId(1))).isEqualTo(1024);
        assertThat(manager.totalSize()).isEqualTo(1024);
    }

    @Test
    void leaderTransferAdvancesConfVer() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        Region transferred = manager.get(new RegionId(1)).withLeader("n2");
        assertThat(transferred.epoch().confVer()).isEqualTo(2);
        assertThat(transferred.leader()).isEqualTo("n2");
    }

    @Test
    void listRegionsContainsAllStates() {
        manager.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                peers(), RegionEpoch.INITIAL, "n1");
        manager.splitRegion(new RegionId(1), bytes("m"));
        assertThat(manager.listRegions()).hasSize(3);
    }

    private static List<String> peers() {
        return List.of("n1", "n2", "n3");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
