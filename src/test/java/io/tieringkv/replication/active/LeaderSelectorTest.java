package io.tieringkv.replication.active;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 全局多活自动选主（ADR-0143）：健康切换 + 仲裁。 */
class LeaderSelectorTest {

    @Test
    void healthyLeaderStays() {
        LeaderSelector selector = new LeaderSelector(Map.of(
                "a", () -> true, "b", () -> true), "a");
        assertThat(selector.selectLeader()).isEqualTo("a");
    }

    @Test
    void failedLeaderSwitches() {
        LeaderSelector selector = new LeaderSelector(Map.of(
                "a", () -> false, "b", () -> true), "a");
        assertThat(selector.selectLeader()).isEqualTo("b");
    }

    @Test
    void allFailedNoLeader() {
        LeaderSelector selector = new LeaderSelector(Map.of(
                "a", () -> false, "b", () -> false), "a");
        assertThat(selector.selectLeader()).isNull();
    }

    @Test
    void majorityHealthyTrue() {
        LeaderSelector selector = new LeaderSelector(Map.of(
                "a", () -> true, "b", () -> true,
                "c", () -> false), "a");
        assertThat(selector.majorityHealthy()).isTrue();
    }

    @Test
    void majorityHealthyFalse() {
        LeaderSelector selector = new LeaderSelector(Map.of(
                "a", () -> false, "b", () -> false,
                "c", () -> true), "a");
        assertThat(selector.majorityHealthy()).isFalse();
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {2, 3, 5})
    void parameterizedRegionCounts(int count) {
        Map<String, java.util.function.BooleanSupplier> health =
                new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            health.put("r" + i, () -> true);
        }
        LeaderSelector selector = new LeaderSelector(health, "r0");
        assertThat(selector.selectLeader()).isEqualTo("r0");
    }

    @ParameterizedTest(name = "down {0}")
    @ValueSource(ints = {0, 1, 2})
    void parameterizedFailures(int down) {
        Map<String, java.util.function.BooleanSupplier> health =
                new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            health.put("r" + i, () -> index != down);
        }
        LeaderSelector selector = new LeaderSelector(health, "r0");
        String leader = selector.selectLeader();
        assertThat(leader).isNotEqualTo("r" + down);
    }

    @Test
    void leaderFieldTracksSelection() {
        LeaderSelector selector = new LeaderSelector(Map.of(
                "a", () -> false, "b", () -> true), "a");
        selector.selectLeader();
        assertThat(selector.leader()).isEqualTo("b");
    }
}
