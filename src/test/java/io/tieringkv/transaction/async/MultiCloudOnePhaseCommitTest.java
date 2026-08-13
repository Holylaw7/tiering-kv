package io.tieringkv.transaction.async;

import io.tieringkv.transaction.async.MultiCloudOnePhaseCommit
        .CloudCommitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨云全局一阶段（ADR-0228）：多数云仲裁 + 回退 2PC + resolved-ts。 */
class MultiCloudOnePhaseCommitTest {

    @Test
    void quorumOnePhase() {
        MultiCloudOnePhaseCommit commit = commit();
        CloudCommitResult result = commit.commit("t1",
                Set.of("aws", "gcp", "azure"));
        assertThat(result.onePhase()).isTrue();
        assertThat(result.succeeded()).isTrue();
        assertThat(result.clouds()).isEqualTo(3);
        assertThat(result.eligibleClouds()).isEqualTo(2);
    }

    @Test
    void minorityFallsBack() {
        MultiCloudOnePhaseCommit commit = commit();
        CloudCommitResult result = commit.commit("t1",
                Set.of("aws", "ali", "other"));
        assertThat(result.onePhase()).isFalse();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void explicitTwoPhase() {
        MultiCloudOnePhaseCommit commit = commit();
        CloudCommitResult result = commit.commitTwoPhase("t1",
                Set.of("aws", "gcp", "azure"));
        assertThat(result.onePhase()).isFalse();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void unknownCloudIneligible() {
        MultiCloudOnePhaseCommit commit = commit();
        assertThat(commit.commit("t1", Set.of("other"))
                .onePhase()).isFalse();
    }

    @Test
    void markUnavailableDegrades() {
        MultiCloudOnePhaseCommit commit = commit();
        commit.markUnavailable("gcp");
        assertThat(commit.commit("t1",
                Set.of("aws", "gcp", "azure")).onePhase())
                .isFalse();
    }

    @Test
    void blankTxnIdRejected() {
        assertThatThrownBy(() -> commit().commit("",
                Set.of("aws")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyCloudsRejected() {
        assertThatThrownBy(() -> commit().commit("t1", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvedTsAdvancedOnQuorum() {
        MultiCloudOnePhaseCommit commit = commit();
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        commit.attachResolvedTimestamp(resolved);
        commit.commit("t1", Set.of("aws", "gcp", "azure"), 100);
        assertThat(resolved.resolvedTs()).isEqualTo(100);
    }

    @Test
    void resolvedTsNotAdvancedOnMinority() {
        MultiCloudOnePhaseCommit commit = commit();
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        commit.attachResolvedTimestamp(resolved);
        commit.commit("t1", Set.of("aws", "ali", "other"), 100);
        assertThat(resolved.resolvedTs()).isZero();
    }

    @Test
    void commitIdempotent() {
        MultiCloudOnePhaseCommit commit = commit();
        CloudCommitResult first = commit.commit("t1",
                Set.of("aws", "gcp", "azure"));
        CloudCommitResult second = commit.commit("t1",
                Set.of("aws", "gcp", "azure"));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void singleCloudQuorum() {
        MultiCloudOnePhaseCommit commit = commit();
        assertThat(commit.commit("t1", Set.of("aws"))
                .onePhase()).isTrue();
    }

    @Test
    void concurrentCommitStable() throws Exception {
        MultiCloudOnePhaseCommit commit = commit();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    assertThat(commit.commit("t" + i,
                            Set.of("aws", "gcp",
                                    "azure")).succeeded())
                            .isTrue();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    @ParameterizedTest(name = "clouds={0} eligible={1}")
    @CsvSource({
            "1,1,true",
            "2,2,true",
            "2,1,false",
            "3,2,true",
            "3,1,false",
            "4,3,true",
            "4,2,false",
            "5,3,true",
            "5,2,false",
            "6,4,true",
            "6,3,false",
            "7,4,true",
            "7,3,false",
            "8,5,true",
            "8,4,false",
            "9,5,true",
            "9,4,false",
            "10,6,true",
            "10,5,false",
            "11,6,true",
            "11,5,false",
            "12,7,true",
            "12,6,false",
            "3,3,true",
            "4,4,true",
            "5,5,true",
            "6,6,true",
            "7,7,true",
            "8,8,true",
            "9,9,true",
            "10,10,true",
            "11,11,true",
            "12,12,true",
            "1,0,false",
            "2,0,false"
    })
    void parameterizedQuorumMatrix(int clouds, int eligible,
                                   boolean expected) {
        MultiCloudOnePhaseCommit commit =
                new MultiCloudOnePhaseCommit();
        for (int i = 0; i < clouds; i++) {
            commit.registerCloud("c" + i, i < eligible);
        }
        Set<String> cloudSet = new HashSet<>();
        for (int i = 0; i < clouds; i++) {
            cloudSet.add("c" + i);
        }
        CloudCommitResult result = commit.commit("t", cloudSet);
        assertThat(result.onePhase()).isEqualTo(expected);
        assertThat(result.eligibleClouds())
                .isEqualTo(Math.min(eligible, clouds));
    }

    @ParameterizedTest(name = "clouds={0} repeats={1}")
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
    void parameterizedCommitIdempotent(int clouds, int repeats) {
        MultiCloudOnePhaseCommit commit =
                new MultiCloudOnePhaseCommit();
        for (int i = 0; i < clouds; i++) {
            commit.registerCloud("c" + i, true);
        }
        Set<String> cloudSet = new HashSet<>();
        for (int i = 0; i < clouds; i++) {
            cloudSet.add("c" + i);
        }
        CloudCommitResult first = commit.commit("t", cloudSet);
        for (int i = 1; i < repeats; i++) {
            assertThat(commit.commit("t", cloudSet))
                    .isEqualTo(first);
        }
    }

    @ParameterizedTest(name = "clouds {0}")
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 9, 11})
    void parameterizedCloudCounts(int clouds) {
        MultiCloudOnePhaseCommit commit =
                new MultiCloudOnePhaseCommit();
        for (int i = 0; i < clouds; i++) {
            commit.registerCloud("c" + i, i != clouds - 1);
        }
        Set<String> cloudSet = new HashSet<>();
        for (int i = 0; i < clouds; i++) {
            cloudSet.add("c" + i);
        }
        boolean quorum = clouds - 1 > clouds / 2;
        assertThat(commit.commit("t", cloudSet).onePhase())
                .isEqualTo(quorum);
    }

    @ParameterizedTest(name = "clouds={0} eligible={1} ts={2}")
    @CsvSource({
            "3,2,100,100",
            "3,3,200,200",
            "5,3,300,300",
            "3,1,100,0",
            "4,2,200,0"
    })
    void parameterizedResolvedTs(int clouds, int eligible,
                                 long ts, long expected) {
        MultiCloudOnePhaseCommit commit =
                new MultiCloudOnePhaseCommit();
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        commit.attachResolvedTimestamp(resolved);
        for (int i = 0; i < clouds; i++) {
            commit.registerCloud("c" + i, i < eligible);
        }
        Set<String> cloudSet = new HashSet<>();
        for (int i = 0; i < clouds; i++) {
            cloudSet.add("c" + i);
        }
        commit.commit("t", cloudSet, ts);
        assertThat(resolved.resolvedTs()).isEqualTo(expected);
    }

    private static MultiCloudOnePhaseCommit commit() {
        MultiCloudOnePhaseCommit commit =
                new MultiCloudOnePhaseCommit();
        commit.registerCloud("aws", true);
        commit.registerCloud("gcp", true);
        commit.registerCloud("azure", false);
        commit.registerCloud("ali", false);
        return commit;
    }
}
