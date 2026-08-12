package io.tieringkv.transaction.async;

import io.tieringkv.transaction.async.GlobalOnePhaseCommit.GlobalCommitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 全局一阶段规模化（ADR-0221）：3 地/5 地 + 回退 2PC + resolved-ts。 */
class GlobalOnePhaseCommitTest {

    @Test
    void allEligibleOnePhase() {
        GlobalOnePhaseCommit commit = commit();
        GlobalCommitResult result = commit.commit("t1",
                Set.of("r1", "r2"));
        assertThat(result.onePhase()).isTrue();
        assertThat(result.succeeded()).isTrue();
        assertThat(result.regions()).isEqualTo(2);
    }

    @Test
    void ineligibleRegionFallsBack() {
        GlobalOnePhaseCommit commit = commit();
        GlobalCommitResult result = commit.commit("t1",
                Set.of("r1", "r3"));
        assertThat(result.onePhase()).isFalse();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void explicitTwoPhase() {
        GlobalOnePhaseCommit commit = commit();
        GlobalCommitResult result = commit.commitTwoPhase("t1",
                Set.of("r1", "r2"));
        assertThat(result.onePhase()).isFalse();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void unknownRegionIneligible() {
        GlobalOnePhaseCommit commit = commit();
        assertThat(commit.commit("t1", Set.of("r9"))
                .onePhase()).isFalse();
    }

    @Test
    void blankTxnIdRejected() {
        assertThatThrownBy(() -> commit().commit("",
                Set.of("r1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyRegionsRejected() {
        assertThatThrownBy(() -> commit().commit("t1", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullRegionsRejected() {
        assertThatThrownBy(() -> commit().commit("t1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void singleEligibleRegionOnePhase() {
        GlobalOnePhaseCommit commit = commit();
        assertThat(commit.commit("t1", Set.of("r1")).onePhase())
                .isTrue();
    }

    @Test
    void commitIdempotent() {
        GlobalOnePhaseCommit commit = commit();
        GlobalCommitResult first = commit.commit("t1",
                Set.of("r1", "r2"));
        GlobalCommitResult second = commit.commit("t1",
                Set.of("r1", "r2"));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void resolvedTsAdvancedOnOnePhase() {
        GlobalOnePhaseCommit commit = commit();
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        commit.attachResolvedTimestamp(resolved);
        commit.commit("t1", Set.of("r1", "r2"), 100);
        assertThat(resolved.resolvedTs()).isEqualTo(100);
    }

    @Test
    void resolvedTsNotAdvancedOnFallback() {
        GlobalOnePhaseCommit commit = commit();
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        commit.attachResolvedTimestamp(resolved);
        commit.commit("t1", Set.of("r1", "r3"), 100);
        assertThat(resolved.resolvedTs()).isZero();
    }

    @Test
    void concurrentCommitStable() throws Exception {
        GlobalOnePhaseCommit commit = commit();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    assertThat(commit.commit("t" + i,
                            Set.of("r1", "r2")).succeeded())
                            .isTrue();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    @ParameterizedTest(name = "eligible={0} regions={1}")
    @CsvSource({
            "1,1,true",
            "2,2,true",
            "3,3,true",
            "4,4,true",
            "5,5,true",
            "1,2,false",
            "2,3,false",
            "3,4,false",
            "4,5,false",
            "1,3,false",
            "2,4,false",
            "3,5,false",
            "1,4,false",
            "2,5,false",
            "0,1,false",
            "0,2,false",
            "0,3,false",
            "1,5,false",
            "2,6,false",
            "3,6,false",
            "4,6,false",
            "5,6,false",
            "6,6,true",
            "1,6,false",
            "2,7,false",
            "3,7,false",
            "4,7,false",
            "5,7,false",
            "6,7,false",
            "7,7,true"
    })
    void parameterizedEligibilityMatrix(int eligible, int regions,
                                        boolean expected) {
        GlobalOnePhaseCommit commit = new GlobalOnePhaseCommit();
        for (int i = 0; i < regions; i++) {
            commit.registerPrimaryReplica("r" + i,
                    i < eligible);
        }
        Set<String> regionSet = new HashSet<>();
        for (int i = 0; i < regions; i++) {
            regionSet.add("r" + i);
        }
        GlobalCommitResult result = commit.commit("t",
                regionSet);
        assertThat(result.onePhase()).isEqualTo(expected);
        assertThat(result.regions()).isEqualTo(regions);
    }

    @ParameterizedTest(name = "regions={0} repeats={1}")
    @CsvSource({
            "1,2",
            "1,5",
            "2,2",
            "2,5",
            "3,2",
            "3,5",
            "4,2",
            "4,5",
            "5,2",
            "5,5",
            "2,10",
            "3,10",
            "4,10",
            "5,10",
            "1,10",
            "3,3",
            "4,4",
            "5,3",
            "2,8",
            "6,2"
    })
    void parameterizedCommitIdempotent(int regions, int repeats) {
        GlobalOnePhaseCommit commit = new GlobalOnePhaseCommit();
        for (int i = 0; i < regions; i++) {
            commit.registerPrimaryReplica("r" + i, true);
        }
        Set<String> regionSet = new HashSet<>();
        for (int i = 0; i < regions; i++) {
            regionSet.add("r" + i);
        }
        GlobalCommitResult first = commit.commit("t",
                regionSet);
        for (int i = 1; i < repeats; i++) {
            assertThat(commit.commit("t", regionSet))
                    .isEqualTo(first);
        }
    }

    @ParameterizedTest(name = "regions={0} eligible={1} ts={2}")
    @CsvSource({
            "1,true,100,100",
            "2,true,200,200",
            "3,true,300,300",
            "4,true,400,400",
            "5,true,500,500",
            "2,false,100,0",
            "3,false,200,0",
            "5,false,300,0"
    })
    void parameterizedResolvedTs(int regions, boolean eligible,
                                 long ts, long expected) {
        GlobalOnePhaseCommit commit = new GlobalOnePhaseCommit();
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        commit.attachResolvedTimestamp(resolved);
        for (int i = 0; i < regions; i++) {
            commit.registerPrimaryReplica("r" + i, eligible);
        }
        Set<String> regionSet = new HashSet<>();
        for (int i = 0; i < regions; i++) {
            regionSet.add("r" + i);
        }
        commit.commit("t", regionSet, ts);
        assertThat(resolved.resolvedTs()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {2, 3, 4, 5, 10})
    void parameterizedRegionCountFallback(int regions) {
        GlobalOnePhaseCommit commit = new GlobalOnePhaseCommit();
        for (int i = 0; i < regions; i++) {
            commit.registerPrimaryReplica("r" + i,
                    i != regions - 1);
        }
        Set<String> regionSet = new HashSet<>();
        for (int i = 0; i < regions; i++) {
            regionSet.add("r" + i);
        }
        assertThat(commit.commit("t", regionSet).onePhase())
                .isFalse();
        assertThat(commit.commit("t", regionSet).succeeded())
                .isTrue();
    }

    private static GlobalOnePhaseCommit commit() {
        GlobalOnePhaseCommit commit = new GlobalOnePhaseCommit();
        commit.registerPrimaryReplica("r1", true);
        commit.registerPrimaryReplica("r2", true);
        commit.registerPrimaryReplica("r3", false);
        return commit;
    }
}
