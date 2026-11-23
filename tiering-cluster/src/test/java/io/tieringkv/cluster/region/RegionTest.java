package io.tieringkv.cluster.region;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Region 模型（ADR-0057）：范围语义 / epoch / 防御性拷贝。 */
class RegionTest {

    private static Region region(byte[] start, byte[] end) {
        return new Region(new RegionId(1), start, end, "n1",
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, RegionState.NORMAL);
    }

    @Test
    void containsStartInclusiveEndExclusive() {
        Region region = region(bytes("a"), bytes("m"));
        assertThat(region.contains(bytes("a"))).isTrue();
        assertThat(region.contains(bytes("k"))).isTrue();
        assertThat(region.contains(bytes("m"))).isFalse();
        assertThat(region.contains(bytes("z"))).isFalse();
        assertThat(region.contains(bytes("0"))).isFalse();
    }

    @Test
    void openEndedRegionContainsEverythingAtOrAfterStart() {
        Region region = region(bytes("m"), null);
        assertThat(region.contains(bytes("m"))).isTrue();
        assertThat(region.contains(bytes("zzz"))).isTrue();
        assertThat(region.contains(bytes("a"))).isFalse();
    }

    @Test
    void emptyStartCoversEverythingUpToEnd() {
        Region region = region(new byte[0], bytes("m"));
        assertThat(region.contains(bytes(""))).isTrue();
        assertThat(region.contains(bytes("a"))).isTrue();
        assertThat(region.contains(bytes("m"))).isFalse();
    }

    @Test
    void invertedRangeRejected() {
        assertThatThrownBy(() -> region(bytes("z"), bytes("a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalRangeRejected() {
        assertThatThrownBy(() -> region(bytes("a"), bytes("a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withLeaderAdvancesConfVer() {
        Region region = region(bytes("a"), bytes("z"));
        Region changed = region.withLeader("n2");
        assertThat(changed.epoch().confVer()).isEqualTo(2);
        assertThat(changed.leader()).isEqualTo("n2");
        assertThat(region.leader()).isEqualTo("n1");
    }

    @Test
    void withStateKeepsEpoch() {
        Region region = region(bytes("a"), bytes("z"));
        Region splitting = region.withState(RegionState.SPLITTING);
        assertThat(splitting.state()).isEqualTo(RegionState.SPLITTING);
        assertThat(splitting.epoch()).isEqualTo(region.epoch());
    }

    @Test
    void epochAdvanceVersion() {
        RegionEpoch epoch = RegionEpoch.INITIAL.advanceVersion();
        assertThat(epoch.version()).isEqualTo(2);
        assertThat(epoch.confVer()).isEqualTo(1);
    }

    @Test
    void epochAdvanceConfVer() {
        RegionEpoch epoch = RegionEpoch.INITIAL.advanceConfVer();
        assertThat(epoch.confVer()).isEqualTo(2);
        assertThat(epoch.version()).isEqualTo(1);
    }

    @Test
    void epochOlderThanDetection() {
        RegionEpoch current = new RegionEpoch(2, 3);
        assertThat(new RegionEpoch(1, 9).olderThan(current)).isTrue();
        assertThat(new RegionEpoch(2, 2).olderThan(current)).isTrue();
        assertThat(new RegionEpoch(2, 3).olderThan(current)).isFalse();
        assertThat(new RegionEpoch(3, 1).olderThan(current)).isFalse();
    }

    @Test
    void regionIdRejectsNegative() {
        assertThatThrownBy(() -> new RegionId(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void regionPeersDefensiveCopy() {
        List<String> mutable = new java.util.ArrayList<>(List.of("n1"));
        Region region = new Region(new RegionId(1), bytes("a"), bytes("z"),
                "n1", mutable, RegionEpoch.INITIAL, RegionState.NORMAL);
        mutable.add("n2");
        assertThat(region.peers()).containsExactly("n1");
    }

    @Test
    void regionKeysDefensiveCopy() {
        byte[] start = bytes("a");
        byte[] end = bytes("z");
        Region region = new Region(new RegionId(1), start, end,
                "n1", List.of("n1"), RegionEpoch.INITIAL, RegionState.NORMAL);
        start[0] = '!';
        end[0] = '!';
        assertThat(region.contains(bytes("a"))).isTrue();
        assertThat(region.contains(bytes("z"))).isFalse();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
