package io.tieringkv.transaction.async;

import io.tieringkv.transaction.async.AsyncCommitCoordinator.CommitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Async Commit + resolved-ts（ADR-0209）：一阶段 + 回退 + 单调。 */
class AsyncCommitTest {

    private final AsyncCommitCoordinator coordinator =
            new AsyncCommitCoordinator();
    private final ResolvedTimestampService resolvedTs =
            new ResolvedTimestampService();

    @Test
    void singleRegionOnePhase() {
        CommitResult result = coordinator.commit("t1", 1);
        assertThat(result.onePhase()).isTrue();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void multiRegionTwoPhase() {
        CommitResult result = coordinator.commit("t1", 3);
        assertThat(result.onePhase()).isFalse();
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void onePhaseRejectsMultiRegion() {
        CommitResult result = coordinator.commitOnePhase("t1", 2);
        assertThat(result.onePhase()).isFalse();
        assertThat(result.succeeded()).isFalse();
    }

    @Test
    void blankTxnIdRejected() {
        assertThatThrownBy(() -> coordinator.commit("", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidRegionCountRejected() {
        assertThatThrownBy(() -> coordinator.commitTwoPhase("t1", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvedTsMonotonic() {
        resolvedTs.advance(100);
        assertThat(resolvedTs.advance(50)).isEqualTo(100);
        assertThat(resolvedTs.advance(100)).isEqualTo(100);
        assertThat(resolvedTs.advance(200)).isEqualTo(200);
    }

    @Test
    void resolvedTsStartsZero() {
        assertThat(resolvedTs.resolvedTs()).isZero();
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {1, 2, 5, 10})
    void parameterizedRegionCounts(int regions) {
        CommitResult result = coordinator.commit("t1", regions);
        assertThat(result.onePhase()).isEqualTo(regions == 1);
        assertThat(result.succeeded()).isTrue();
    }

    @ParameterizedTest(name = "ts {0}")
    @ValueSource(longs = {0, 100, 1000, 10_000})
    void parameterizedResolvedTs(long ts) {
        assertThat(resolvedTs.advance(ts)).isGreaterThanOrEqualTo(ts);
    }

    @Test
    void concurrentResolvedTsMonotonic() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    resolvedTs.advance(i);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(resolvedTs.resolvedTs()).isEqualTo(99);
    }

    @Test
    void concurrentCommitsStable() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    CommitResult result = coordinator.commit(
                            "t" + i, 1);
                    assertThat(result.succeeded()).isTrue();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    @Test
    void commitCarriesTxnId() {
        assertThat(coordinator.commit("txn-9", 1).txnId())
                .isEqualTo("txn-9");
    }
}
