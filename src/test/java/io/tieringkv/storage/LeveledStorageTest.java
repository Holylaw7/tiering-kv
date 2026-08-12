package io.tieringkv.storage;

import io.tieringkv.storage.compaction.LeveledCompactionPlanner;
import io.tieringkv.storage.compaction.LeveledCompactionPlanner.CompactionPlan;
import io.tieringkv.storage.memory.ImmutableMemTableRotator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Leveled LSM + Immutable 轮转（ADR-0204）。 */
class LeveledStorageTest {

    private final LeveledCompactionPlanner planner =
            new LeveledCompactionPlanner();

    @Test
    void shouldCompactWhenOverLimit() {
        assertThat(planner.shouldCompact(200, 100)).isTrue();
        assertThat(planner.shouldCompact(100, 100)).isFalse();
        assertThat(planner.shouldCompact(50, 100)).isFalse();
    }

    @Test
    void planLevelOverLimit() {
        CompactionPlan plan = planner.planLevel(200, 100, 64,
                0);
        assertThat(plan.sourceLevel()).isZero();
        assertThat(plan.targetLevel()).isEqualTo(1);
        assertThat(plan.fileCount()).isEqualTo(4);
    }

    @Test
    void planLevelWithinLimitNoFiles() {
        CompactionPlan plan = planner.planLevel(50, 100, 64, 1);
        assertThat(plan.fileCount()).isZero();
    }

    @Test
    void invalidSizesRejected() {
        assertThatThrownBy(() -> planner.shouldCompact(-1, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner.planLevel(100, 50, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "bytes {0}")
    @ValueSource(longs = {0, 64, 128, 256, 1000})
    void parameterizedLevelBytes(long bytes) {
        CompactionPlan plan = planner.planLevel(bytes, 500, 128,
                2);
        assertThat(plan.sourceLevel()).isEqualTo(2);
        assertThat(plan.targetLevel()).isEqualTo(3);
        assertThat(plan.fileCount())
                .isEqualTo(bytes > 500
                        ? (int) Math.ceil(bytes / 128.0) : 0);
    }

    @Test
    void rotateMovesActiveToImmutable() {
        ImmutableMemTableRotator rotator =
                new ImmutableMemTableRotator();
        String active = rotator.activeId();
        String next = rotator.rotate();
        assertThat(next).isNotEqualTo(active);
        assertThat(rotator.immutableCount()).isEqualTo(1);
        assertThat(rotator.immutables()).contains(active);
        assertThat(rotator.activeId()).isEqualTo(next);
    }

    @Test
    void flushDoneRemovesImmutable() {
        ImmutableMemTableRotator rotator =
                new ImmutableMemTableRotator();
        String active = rotator.activeId();
        rotator.rotate();
        assertThat(rotator.flushDone(active)).isTrue();
        assertThat(rotator.immutableCount()).isZero();
        assertThat(rotator.flushDone(active)).isFalse();
    }

    @Test
    void pendingFlushListed() {
        ImmutableMemTableRotator rotator =
                new ImmutableMemTableRotator();
        String first = rotator.activeId();
        rotator.rotate();
        String second = rotator.activeId();
        rotator.rotate();
        assertThat(rotator.pendingFlush())
                .containsExactlyInAnyOrder(first, second);
    }

    @ParameterizedTest(name = "rotations {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedRotations(int count) {
        ImmutableMemTableRotator rotator =
                new ImmutableMemTableRotator();
        for (int i = 0; i < count; i++) {
            rotator.rotate();
        }
        assertThat(rotator.immutableCount()).isEqualTo(count);
    }

    @Test
    void concurrentRotateStable() throws Exception {
        ImmutableMemTableRotator rotator =
                new ImmutableMemTableRotator();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 25; i++) {
                    rotator.rotate();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(rotator.immutableCount()).isEqualTo(100);
    }
}
