package io.tieringkv.replication.active;

import io.tieringkv.replication.active.RaftAwareLeaderSelector.RegionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 选主与 Raft term 联动（ADR-0145）：term 单调 + 防脑裂 + 故障切换。 */
class RaftAwareLeaderSelectorTest {

    @Test
    void keepsHealthyCurrentTermLeader() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true),
                        "r2", new RegionState(4, true),
                        "r3", new RegionState(5, true)), "r1");
        assertThat(selector.selectLeader()).isEqualTo("r1");
    }

    @Test
    void failsOverWhenLeaderUnhealthy() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, false),
                        "r2", new RegionState(4, true),
                        "r3", new RegionState(5, true)), "r1");
        assertThat(selector.selectLeader()).isEqualTo("r3");
    }

    @Test
    void failsOverToHighestTermHealthy() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true),
                        "r2", new RegionState(7, true),
                        "r3", new RegionState(6, true)), "r1");
        assertThat(selector.selectLeader()).isEqualTo("r2");
    }

    @Test
    void lowTermSelfPromotionRejected() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true),
                        "r2", new RegionState(4, true)), "r1");
        assertThat(selector.tryBecomeLeader("r2", 3)).isFalse();
        assertThat(selector.leader()).isEqualTo("r1");
    }

    @Test
    void equalTermSelfPromotionAcceptedWhenHealthy() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true),
                        "r2", new RegionState(5, true)), "r1");
        assertThat(selector.tryBecomeLeader("r2", 5)).isTrue();
        assertThat(selector.leader()).isEqualTo("r2");
    }

    @Test
    void higherTermSelfPromotionAccepted() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true),
                        "r2", new RegionState(5, true)), "r1");
        assertThat(selector.tryBecomeLeader("r2", 8)).isTrue();
        assertThat(selector.currentTerm()).isEqualTo(8);
    }

    @Test
    void unhealthySelfPromotionRejected() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true),
                        "r2", new RegionState(9, false)), "r1");
        assertThat(selector.tryBecomeLeader("r2", 9)).isFalse();
        assertThat(selector.leader()).isEqualTo("r1");
    }

    @Test
    void unknownRegionSelfPromotionRejected() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true)), "r1");
        assertThat(selector.tryBecomeLeader("r9", 99)).isFalse();
    }

    @Test
    void staleLeaderDemotedAfterTermUpdate() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true),
                        "r2", new RegionState(5, true)), "r1");
        selector.updateRegion("r2", 6, true);
        assertThat(selector.selectLeader()).isEqualTo("r2");
        assertThat(selector.leader()).isEqualTo("r2");
    }

    @Test
    void currentTermMonotonicOnUpdate() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true)), "r1");
        selector.updateRegion("r1", 3, true);
        assertThat(selector.currentTerm()).isEqualTo(5);
        selector.updateRegion("r1", 7, true);
        assertThat(selector.currentTerm()).isEqualTo(7);
    }

    @Test
    void noLeaderWhenAllUnhealthy() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, false),
                        "r2", new RegionState(6, false)), "r1");
        assertThat(selector.selectLeader()).isNull();
    }

    @Test
    void majorityHealthy() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true),
                        "r2", new RegionState(4, true),
                        "r3", new RegionState(5, false)), "r1");
        assertThat(selector.majorityHealthy()).isTrue();
    }

    @Test
    void noMajorityWhenHalfUnhealthy() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true),
                        "r2", new RegionState(4, false)), "r1");
        assertThat(selector.majorityHealthy()).isFalse();
    }

    @Test
    void updateMakesRegionEligible() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true),
                        "r2", new RegionState(3, false)), "r1");
        selector.updateRegion("r2", 6, true);
        assertThat(selector.selectLeader()).isEqualTo("r2");
    }

    @Test
    void recoveredRegionWithSameTermCanLead() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true),
                        "r2", new RegionState(5, false)), "r1");
        selector.updateRegion("r2", 5, true);
        assertThat(selector.selectLeader()).isEqualTo("r1");
        selector.tryBecomeLeader("r2", 5);
        assertThat(selector.leader()).isEqualTo("r2");
    }

    @ParameterizedTest(name = "term {0} vs current {1}")
    @CsvSource({
            "4,5",
            "-1,5",
            "5,5",
            "6,5"
    })
    void parameterizedTermComparison(long candidateTerm, long current) {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(current, true),
                        "r2", new RegionState(current, true)), "r1");
        boolean accepted = candidateTerm >= current;
        assertThat(selector.tryBecomeLeader("r2", candidateTerm))
                .isEqualTo(accepted);
        assertThat(selector.currentTerm())
                .isEqualTo(Math.max(current, candidateTerm));
    }

    @ParameterizedTest(name = "healthy {0}/{1}")
    @CsvSource({"1,1", "1,2", "2,3", "2,2", "3,3"})
    void parameterizedMajority(long healthy, long total) {
        Map<String, RegionState> states = new LinkedHashMap<>();
        for (int i = 0; i < total; i++) {
            states.put("r" + i, new RegionState(5,
                    i < healthy));
        }
        RaftAwareLeaderSelector selector = selector(states, "r0");
        assertThat(selector.majorityHealthy())
                .isEqualTo(healthy * 2 > total);
    }

    @Test
    void electionAfterPartitionRecovery() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(9, true),
                        "r2", new RegionState(8, false),
                        "r3", new RegionState(9, true)), "r1");
        selector.updateRegion("r1", 9, false);
        assertThat(selector.selectLeader()).isEqualTo("r3");
        selector.updateRegion("r2", 10, true);
        assertThat(selector.selectLeader()).isEqualTo("r2");
    }

    @Test
    void repeatedSelectionStable() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true),
                        "r2", new RegionState(5, true),
                        "r3", new RegionState(5, true)), "r1");
        String first = selector.selectLeader();
        for (int i = 0; i < 10; i++) {
            assertThat(selector.selectLeader()).isEqualTo(first);
        }
    }

    @Test
    void tryBecomeLeaderNeverLowersTerm() {
        RaftAwareLeaderSelector selector = selector(
                Map.of("r1", new RegionState(5, true),
                        "r2", new RegionState(5, true)), "r1");
        selector.tryBecomeLeader("r2", 7);
        assertThat(selector.tryBecomeLeader("r1", 6)).isFalse();
        assertThat(selector.leader()).isEqualTo("r2");
    }

    @Test
    void initialLeaderNullWhenRegionsEmpty() {
        RaftAwareLeaderSelector selector = selector(Map.of(), null);
        assertThat(selector.selectLeader()).isNull();
        assertThat(selector.majorityHealthy()).isFalse();
    }

    private static RaftAwareLeaderSelector selector(
            Map<String, RegionState> regions, String initialLeader) {
        return new RaftAwareLeaderSelector(regions, initialLeader);
    }
}
