package io.tieringkv.cluster.placement;

import io.tieringkv.cluster.region.Region;
import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 放置控制（ADR-0060）：分布 / 均衡 / leader 转移（无自动 rebalance）。 */
class PlacementManagerTest {

    private RegionManager regions;
    private PlacementManager placement;

    @BeforeEach
    void setUp() {
        regions = new RegionManager();
        placement = new PlacementManager(regions);
    }

    @Test
    void distributionListsRegionsPerNode() {
        regions.createRegion(new RegionId(1), bytes("a"), bytes("m"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(2), bytes("m"), bytes("z"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n2");
        Map<String, List<RegionId>> distribution = placement.distribution();
        assertThat(distribution.get("n1")).containsExactlyInAnyOrder(
                new RegionId(1), new RegionId(2));
        assertThat(distribution.get("n2")).containsExactlyInAnyOrder(
                new RegionId(1), new RegionId(2));
        assertThat(distribution.get("n3")).containsExactlyInAnyOrder(
                new RegionId(1), new RegionId(2));
    }

    @Test
    void balancedClusterDetected() {
        balanced(3);
        assertThat(placement.isBalanced(0)).isTrue();
        assertThat(placement.balanceSkew()).isZero();
    }

    @Test
    void unbalancedClusterDetected() {
        regions.createRegion(new RegionId(1), bytes("a"), bytes("m"),
                List.of("n1"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(2), bytes("m"), bytes("z"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n2");
        assertThat(placement.balanceSkew()).isGreaterThan(0);
        assertThat(placement.isBalanced(0)).isFalse();
        assertThat(placement.isBalanced(1)).isTrue();
    }

    @Test
    void transferLeaderUpdatesRegion() {
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n1");
        Region updated = placement.transferLeader(new RegionId(1), "n2");
        assertThat(updated.leader()).isEqualTo("n2");
        assertThat(regions.get(new RegionId(1)).leader()).isEqualTo("n2");
    }

    @Test
    void transferLeaderAdvancesConfVer() {
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n1");
        Region updated = placement.transferLeader(new RegionId(1), "n2");
        assertThat(updated.epoch().confVer()).isEqualTo(2);
    }

    @Test
    void transferLeaderToNonPeerRejected() {
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        assertThatThrownBy(() -> placement.transferLeader(new RegionId(1), "n9"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transferLeaderTombstoneRejected() {
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n1");
        regions.splitRegion(new RegionId(1), bytes("m"));
        assertThatThrownBy(() -> placement.transferLeader(new RegionId(1), "n2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void balanceSkewComputed() {
        balanced(3);
        assertThat(placement.maxRegionsPerNode()).isEqualTo(3);
        assertThat(placement.minRegionsPerNode()).isEqualTo(3);
        assertThat(placement.balanceSkew()).isZero();
    }

    @Test
    void distributionExcludesTombstones() {
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n1");
        regions.splitRegion(new RegionId(1), bytes("m"));
        assertThat(placement.distribution().values().stream()
                .flatMap(List::stream)
                .noneMatch(id -> id.equals(new RegionId(1)))).isTrue();
        assertThat(placement.distribution().values().stream()
                .flatMap(List::stream).count()).isEqualTo(6);
    }

    @Test
    void transferLeaderKeepsRouting() {
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n1");
        placement.transferLeader(new RegionId(1), "n3");
        assertThat(regions.route(bytes("b")).leader()).isEqualTo("n3");
    }

    @Test
    void noAutoRebalanceAfterTransfer() {
        balanced(3);
        placement.transferLeader(new RegionId(1), "n2");
        assertThat(placement.balanceSkew()).isZero();
        assertThat(placement.distribution().get("n1")).hasSize(3);
    }

    @Test
    void transferLeaderSameLeaderAdvancesEpoch() {
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n1");
        Region updated = placement.transferLeader(new RegionId(1), "n1");
        assertThat(updated.epoch().confVer()).isEqualTo(2);
    }

    private void balanced(int regionCount) {
        for (int i = 0; i < regionCount; i++) {
            regions.createRegion(new RegionId(i + 1),
                    bytes("k" + i), bytes("k" + (i + 1)),
                    List.of("n1", "n2", "n3"), RegionEpoch.INITIAL,
                    "n" + (i % 3 + 1));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
