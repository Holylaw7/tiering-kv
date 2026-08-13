package io.tieringkv.transaction.async;

import io.tieringkv.transaction.async.MultiCloudOnePhaseScaleOut
        .ScaleOutResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨云一阶段规模化（ADR-0235）：云 × 区分层仲裁。 */
class MultiCloudOnePhaseScaleOutTest {

    @Test
    void twoCloudsEachMajorityZoneOnePhase() {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                2, 3, 2);
        ScaleOutResult result = scaleOut.commit("t1",
                topology(2, 3));
        assertThat(result.onePhase()).isTrue();
        assertThat(result.succeeded()).isTrue();
        assertThat(result.eligibleClouds()).isEqualTo(2);
    }

    @Test
    void minorityCloudFallsBack() {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                2, 3, 1);
        ScaleOutResult result = scaleOut.commit("t1",
                topology(2, 3));
        assertThat(result.onePhase()).isFalse();
    }

    @Test
    void zoneMinorityFailsCloud() {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                3, 2, 1);
        ScaleOutResult result = scaleOut.commit("t1",
                topology(3, 2));
        assertThat(result.onePhase()).isFalse();
        assertThat(result.eligibleClouds()).isZero();
    }

    @Test
    void markCloudUnavailableFallsBack() {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                2, 3, 2);
        scaleOut.markCloudUnavailable("c1");
        assertThat(scaleOut.commit("t1",
                topology(2, 3)).onePhase()).isFalse();
    }

    @Test
    void blankTxnIdRejected() {
        assertThatThrownBy(() -> scaleOut(2, 3, 2)
                .commit("", topology(2, 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyTopologyRejected() {
        assertThatThrownBy(() -> scaleOut(2, 3, 2)
                .commit("t1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cloudWithoutZonesRejected() {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                2, 3, 2);
        Map<String, Set<String>> bad = new LinkedHashMap<>();
        bad.put("c1", Set.of("z1", "z2", "z3"));
        bad.put("c2", Set.of());
        assertThatThrownBy(() -> scaleOut.commit("t1", bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvedTsAdvancedOnQuorum() {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                2, 3, 2);
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        scaleOut.attachResolvedTimestamp(resolved);
        scaleOut.commit("t1", topology(2, 3), 100);
        assertThat(resolved.resolvedTs()).isEqualTo(100);
    }

    @Test
    void resolvedTsNotAdvancedOnMinority() {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                2, 3, 1);
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        scaleOut.attachResolvedTimestamp(resolved);
        scaleOut.commit("t1", topology(2, 3), 100);
        assertThat(resolved.resolvedTs()).isZero();
    }

    @Test
    void commitIdempotent() {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                2, 3, 2);
        ScaleOutResult first = scaleOut.commit("t1",
                topology(2, 3));
        ScaleOutResult second = scaleOut.commit("t1",
                topology(2, 3));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void singleCloudQuorum() {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                1, 3, 2);
        assertThat(scaleOut.commit("t1",
                topology(1, 3)).onePhase()).isTrue();
    }

    @Test
    void concurrentCommitStable() throws Exception {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                3, 3, 2);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    assertThat(scaleOut.commit("t" + i,
                            topology(3, 3)).succeeded())
                            .isTrue();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    @ParameterizedTest(name = "clouds={0} zones={1} eligible={2}")
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
    void parameterizedTopologyMatrix(int clouds, int zones,
                                     int eligibleZones,
                                     boolean expected) {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                clouds, zones, eligibleZones);
        ScaleOutResult result = scaleOut.commit("t",
                topology(clouds, zones));
        assertThat(result.onePhase()).isEqualTo(expected);
        assertThat(result.clouds()).isEqualTo(clouds);
        assertThat(result.zones())
                .isEqualTo(clouds * zones);
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
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                clouds, zones, Math.max(1, zones / 2 + 1));
        ScaleOutResult first = scaleOut.commit("t",
                topology(clouds, zones));
        for (int i = 1; i < repeats; i++) {
            assertThat(scaleOut.commit("t",
                    topology(clouds, zones)))
                    .isEqualTo(first);
        }
    }

    @ParameterizedTest(name = "clouds {0}")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 9, 11})
    void parameterizedCloudCounts(int clouds) {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                clouds, 3, 2);
        ScaleOutResult result = scaleOut.commit("t",
                topology(clouds, 3));
        assertThat(result.onePhase()).isTrue();
    }

    @ParameterizedTest(name = "clouds={0} zones={1} elig={2} ts={3}")
    @CsvSource({
            "2,3,2,100,100",
            "3,3,2,200,200",
            "5,3,2,300,300",
            "2,3,1,100,0",
            "3,2,1,200,0"
    })
    void parameterizedResolvedTs(int clouds, int zones,
                                 int eligibleZones, long ts,
                                 long expected) {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut(
                clouds, zones, eligibleZones);
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        scaleOut.attachResolvedTimestamp(resolved);
        scaleOut.commit("t", topology(clouds, zones), ts);
        assertThat(resolved.resolvedTs()).isEqualTo(expected);
    }

    private static MultiCloudOnePhaseScaleOut scaleOut(
            int clouds, int zones, int eligibleZones) {
        MultiCloudOnePhaseScaleOut scaleOut =
                new MultiCloudOnePhaseScaleOut();
        for (int c = 1; c <= clouds; c++) {
            for (int z = 1; z <= zones; z++) {
                scaleOut.registerZone("c" + c, "z" + z,
                        z <= eligibleZones);
            }
        }
        return scaleOut;
    }

    private static Map<String, Set<String>> topology(
            int clouds, int zones) {
        Map<String, Set<String>> topology =
                new LinkedHashMap<>();
        for (int c = 1; c <= clouds; c++) {
            Set<String> zoneSet = new HashSet<>();
            for (int z = 1; z <= zones; z++) {
                zoneSet.add("z" + z);
            }
            topology.put("c" + c, zoneSet);
        }
        return topology;
    }
}
