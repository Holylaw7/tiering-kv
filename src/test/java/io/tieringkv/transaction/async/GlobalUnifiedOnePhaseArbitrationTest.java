package io.tieringkv.transaction.async;

import io.tieringkv.transaction.async.GlobalUnifiedOnePhaseArbitration
        .UnifiedResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨云一阶段全球统一（ADR-0242）：任意拓扑自动仲裁。 */
class GlobalUnifiedOnePhaseArbitrationTest {

    @Test
    void allEligibleAutoArbitration() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(3, 3, 2);
        UnifiedResult result = arbitration.commit("t1",
                Set.of("c1", "c2", "c3"));
        assertThat(result.onePhase()).isTrue();
        assertThat(result.eligibleClouds()).isEqualTo(3);
        assertThat(result.eligibleZones()).isEqualTo(6);
    }

    @Test
    void minorityFallsBack() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(3, 3, 2);
        assertThat(arbitration.commit("t1",
                Set.of("c1", "other", "extra")).onePhase())
                .isFalse();
    }

    @Test
    void zoneMinorityFailsCloud() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(3, 2, 1);
        UnifiedResult result = arbitration.commit("t1",
                Set.of("c1", "c2", "c3"));
        assertThat(result.onePhase()).isFalse();
        assertThat(result.eligibleClouds()).isZero();
    }

    @Test
    void markCloudUnavailableFallsBack() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(3, 3, 2);
        arbitration.markCloudUnavailable("c2");
        arbitration.markCloudUnavailable("c3");
        assertThat(arbitration.commit("t1",
                Set.of("c1", "c2", "c3")).onePhase()).isFalse();
    }

    @Test
    void topologyVersionIncrements() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(1, 1, 1);
        long v1 = arbitration.topologyVersion();
        arbitration.registerZone("c1", "z2", true);
        assertThat(arbitration.topologyVersion())
                .isGreaterThan(v1);
    }

    @Test
    void blankTxnIdRejected() {
        assertThatThrownBy(() -> arbitration(2, 3, 2)
                .commit("", Set.of("c1", "c2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyCloudsRejected() {
        assertThatThrownBy(() -> arbitration(2, 3, 2)
                .commit("t1", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvedTsAdvanced() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(3, 3, 2);
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        arbitration.attachResolvedTimestamp(resolved);
        arbitration.commit("t1",
                Set.of("c1", "c2", "c3"), 100);
        assertThat(resolved.resolvedTs()).isEqualTo(100);
    }

    @Test
    void resolvedTsNotAdvancedOnMinority() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(3, 3, 2);
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        arbitration.attachResolvedTimestamp(resolved);
        arbitration.commit("t1",
                Set.of("c1", "other", "extra"), 100);
        assertThat(resolved.resolvedTs()).isZero();
    }

    @Test
    void commitIdempotent() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(3, 3, 2);
        UnifiedResult first = arbitration.commit("t1",
                Set.of("c1", "c2", "c3"));
        UnifiedResult second = arbitration.commit("t1",
                Set.of("c1", "c2", "c3"));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void singleCloudQuorum() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(1, 3, 2);
        assertThat(arbitration.commit("t1",
                Set.of("c1")).onePhase()).isTrue();
    }

    @Test
    void concurrentCommitStable() throws Exception {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(3, 3, 2);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    assertThat(arbitration.commit("t" + i,
                            Set.of("c1", "c2",
                                    "c3")).succeeded())
                            .isTrue();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    @ParameterizedTest(name = "clouds={0} zones={1} elig={2}")
    @CsvSource({
            "1,1,1,true",
            "1,2,2,true",
            "1,2,1,false",
            "1,3,2,true",
            "1,3,1,false",
            "2,3,2,true",
            "2,3,1,false",
            "2,2,2,true",
            "2,2,1,false",
            "3,3,2,true",
            "3,3,1,false",
            "3,2,2,true",
            "3,2,1,false",
            "4,3,2,true",
            "4,3,1,false",
            "4,4,3,true",
            "4,4,2,false",
            "5,3,2,true",
            "5,3,1,false",
            "5,5,3,true",
            "5,5,2,false",
            "6,3,2,true",
            "6,3,1,false",
            "6,4,3,true",
            "6,4,2,false",
            "7,3,2,true",
            "7,3,1,false",
            "7,5,3,true",
            "7,5,2,false",
            "8,3,2,true",
            "8,3,1,false",
            "8,6,4,true",
            "8,6,3,false",
            "9,3,2,true",
            "9,3,1,false"
    })
    void parameterizedArbitrationMatrix(int clouds, int zones,
                                        int eligibleZones,
                                        boolean expected) {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(clouds, zones, eligibleZones);
        Set<String> cloudSet = new HashSet<>();
        for (int c = 1; c <= clouds; c++) {
            cloudSet.add("c" + c);
        }
        assertThat(arbitration.commit("t", cloudSet)
                .onePhase()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "clouds={0} zones={1} repeats={2}")
    @CsvSource({
            "1,3,2",
            "1,3,5",
            "2,3,2",
            "2,3,5",
            "3,3,2",
            "3,3,5",
            "4,3,2",
            "4,3,5",
            "5,3,2",
            "5,3,5",
            "2,2,10",
            "3,2,10",
            "4,2,10",
            "5,2,10",
            "1,2,10",
            "3,4,3",
            "4,4,4",
            "5,4,3",
            "2,5,8",
            "6,2,2"
    })
    void parameterizedCommitIdempotent(int clouds, int zones,
                                       int repeats) {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(clouds, zones,
                        Math.max(1, zones / 2 + 1));
        Set<String> cloudSet = new HashSet<>();
        for (int c = 1; c <= clouds; c++) {
            cloudSet.add("c" + c);
        }
        UnifiedResult first = arbitration.commit("t",
                cloudSet);
        for (int i = 1; i < repeats; i++) {
            assertThat(arbitration.commit("t", cloudSet))
                    .isEqualTo(first);
        }
    }

    @ParameterizedTest(name = "clouds {0}")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 9, 11})
    void parameterizedCloudCounts(int clouds) {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(clouds, 3, 2);
        Set<String> cloudSet = new HashSet<>();
        for (int c = 1; c <= clouds; c++) {
            cloudSet.add("c" + c);
        }
        assertThat(arbitration.commit("t", cloudSet)
                .onePhase()).isTrue();
    }

    @ParameterizedTest(name = "clouds={0} zones={1} elig={2} ts={3}")
    @CsvSource({
            "3,3,2,100,100",
            "3,3,3,200,200",
            "5,3,2,300,300",
            "3,3,1,100,0",
            "3,2,1,200,0"
    })
    void parameterizedResolvedTs(int clouds, int zones,
                                 int eligibleZones, long ts,
                                 long expected) {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration(clouds, zones, eligibleZones);
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        arbitration.attachResolvedTimestamp(resolved);
        Set<String> cloudSet = new HashSet<>();
        for (int c = 1; c <= clouds; c++) {
            cloudSet.add("c" + c);
        }
        arbitration.commit("t", cloudSet, ts);
        assertThat(resolved.resolvedTs()).isEqualTo(expected);
    }

    private static GlobalUnifiedOnePhaseArbitration arbitration(
            int clouds, int zones, int eligibleZones) {
        GlobalUnifiedOnePhaseArbitration arbitration =
                new GlobalUnifiedOnePhaseArbitration();
        for (int c = 1; c <= clouds; c++) {
            for (int z = 1; z <= zones; z++) {
                arbitration.registerZone("c" + c, "z" + z,
                        z <= eligibleZones);
            }
        }
        return arbitration;
    }
}
