package io.tieringkv.transaction.async;

import io.tieringkv.transaction.async.CrossRegionOnePhaseCommit.CommitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨区一阶段（ADR-0214）：主副本资格 + 回退 2PC。 */
class CrossRegionOnePhaseCommitTest {

    @Test
    void allEligibleOnePhase() {
        CrossRegionOnePhaseCommit commit = commit();
        CommitResult result = commit.commit("t1",
                Set.of("r1", "r2"));
        assertThat(result.onePhase()).isTrue();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void ineligibleRegionFallsBack() {
        CrossRegionOnePhaseCommit commit = commit();
        CommitResult result = commit.commit("t1",
                Set.of("r1", "r3"));
        assertThat(result.onePhase()).isFalse();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void explicitTwoPhase() {
        CrossRegionOnePhaseCommit commit = commit();
        CommitResult result = commit.commitTwoPhase("t1",
                Set.of("r1", "r2"));
        assertThat(result.onePhase()).isFalse();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void unknownRegionIneligible() {
        CrossRegionOnePhaseCommit commit = commit();
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
        CrossRegionOnePhaseCommit commit = commit();
        assertThat(commit.commit("t1", Set.of("r1")).onePhase())
                .isTrue();
    }

    @Test
    void commitIdempotent() {
        CrossRegionOnePhaseCommit commit = commit();
        CommitResult first = commit.commit("t1",
                Set.of("r1", "r2"));
        CommitResult second = commit.commit("t1",
                Set.of("r1", "r2"));
        assertThat(second).isEqualTo(first);
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedRegionCounts(int count) {
        CrossRegionOnePhaseCommit commit =
                new CrossRegionOnePhaseCommit();
        for (int i = 0; i < count; i++) {
            commit.registerPrimaryReplica("r" + i, true);
        }
        java.util.Set<String> regions =
                new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            regions.add("r" + i);
        }
        CommitResult result = commit.commit("t1", regions);
        assertThat(result.onePhase()).isTrue();
    }

    @Test
    void concurrentCommitStable() throws Exception {
        CrossRegionOnePhaseCommit commit = commit();
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

    private static CrossRegionOnePhaseCommit commit() {
        CrossRegionOnePhaseCommit commit =
                new CrossRegionOnePhaseCommit();
        commit.registerPrimaryReplica("r1", true);
        commit.registerPrimaryReplica("r2", true);
        commit.registerPrimaryReplica("r3", false);
        return commit;
    }

    @Test
    void reRegistrationChangesEligibility() {
        CrossRegionOnePhaseCommit commit =
                new CrossRegionOnePhaseCommit();
        commit.registerPrimaryReplica("r1", true);
        assertThat(commit.commit("t1",
                Set.of("r1")).onePhase()).isTrue();
        commit.registerPrimaryReplica("r1", false);
        assertThat(commit.commit("t1",
                Set.of("r1")).onePhase()).isFalse();
    }

    @Test
    void twoPhaseIdempotent() {
        CrossRegionOnePhaseCommit commit = commit();
        CommitResult first = commit.commitTwoPhase("t1",
                Set.of("r1", "r2"));
        CommitResult second = commit.commitTwoPhase("t1",
                Set.of("r1", "r2"));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void mixedRollingUpdateEligibility() {
        CrossRegionOnePhaseCommit commit =
                new CrossRegionOnePhaseCommit();
        commit.registerPrimaryReplica("r1", true);
        commit.registerPrimaryReplica("r2", false);
        commit.registerPrimaryReplica("r3", true);
        assertThat(commit.commit("t1",
                Set.of("r1", "r3")).onePhase()).isTrue();
        assertThat(commit.commit("t1",
                Set.of("r1", "r2", "r3")).onePhase()).isFalse();
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
        CrossRegionOnePhaseCommit commit =
                new CrossRegionOnePhaseCommit();
        for (int i = 0; i < regions; i++) {
            commit.registerPrimaryReplica("r" + i,
                    i < eligible);
        }
        Set<String> regionSet = new java.util.HashSet<>();
        for (int i = 0; i < regions; i++) {
            regionSet.add("r" + i);
        }
        assertThat(commit.commit("t", regionSet).onePhase())
                .isEqualTo(expected);
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
            "1,10"
    })
    void parameterizedCommitIdempotent(int regions, int repeats) {
        CrossRegionOnePhaseCommit commit =
                new CrossRegionOnePhaseCommit();
        for (int i = 0; i < regions; i++) {
            commit.registerPrimaryReplica("r" + i, true);
        }
        Set<String> regionSet = new java.util.HashSet<>();
        for (int i = 0; i < regions; i++) {
            regionSet.add("r" + i);
        }
        CommitResult first = commit.commit("t", regionSet);
        for (int i = 1; i < repeats; i++) {
            assertThat(commit.commit("t", regionSet))
                    .isEqualTo(first);
        }
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10})
    void parameterizedRegionCountFallback(int regions) {
        CrossRegionOnePhaseCommit commit =
                new CrossRegionOnePhaseCommit();
        for (int i = 0; i < regions; i++) {
            commit.registerPrimaryReplica("r" + i,
                    i != regions - 1);
        }
        Set<String> regionSet = new java.util.HashSet<>();
        for (int i = 0; i < regions; i++) {
            regionSet.add("r" + i);
        }
        assertThat(commit.commit("t", regionSet).onePhase())
                .isFalse();
        assertThat(commit.commit("t", regionSet).succeeded())
                .isTrue();
    }
}
