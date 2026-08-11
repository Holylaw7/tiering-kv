package io.tieringkv.vector.cluster;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 向量双写迁移（ADR-0134）：窗口双写 + 切换 + 回滚。 */
class VectorDoubleWriteTest {

    @Test
    void normalPutOnlyPrimary() {
        VectorStore primary = new VectorStore();
        VectorStore secondary = new VectorStore();
        VectorDoubleWriteRouter router =
                new VectorDoubleWriteRouter(primary, secondary);
        router.put(new Embedding("a", new float[]{1, 0}));
        assertThat(router.primarySize()).isEqualTo(1);
        assertThat(router.secondarySize()).isZero();
    }

    @Test
    void migrationDoubleWrites() {
        VectorStore primary = new VectorStore();
        VectorStore secondary = new VectorStore();
        VectorDoubleWriteRouter router =
                new VectorDoubleWriteRouter(primary, secondary);
        router.beginMigration();
        router.put(new Embedding("a", new float[]{1, 0}));
        assertThat(router.primarySize()).isEqualTo(1);
        assertThat(router.secondarySize()).isEqualTo(1);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 50, 200})
    void parameterizedDoubleWrite(int count) {
        VectorStore primary = new VectorStore();
        VectorStore secondary = new VectorStore();
        VectorDoubleWriteRouter router =
                new VectorDoubleWriteRouter(primary, secondary);
        router.beginMigration();
        for (int i = 0; i < count; i++) {
            router.put(new Embedding("e" + i,
                    new float[]{i % 3, 3 - i % 3}));
        }
        assertThat(router.primarySize()).isEqualTo(count);
        assertThat(router.secondarySize()).isEqualTo(count);
    }

    @Test
    void commitSwitchStopsDoubleWrite() {
        VectorStore primary = new VectorStore();
        VectorStore secondary = new VectorStore();
        VectorDoubleWriteRouter router =
                new VectorDoubleWriteRouter(primary, secondary);
        router.beginMigration();
        router.put(new Embedding("a", new float[]{1, 0}));
        router.commitSwitch();
        router.put(new Embedding("b", new float[]{0, 1}));
        assertThat(router.primarySize()).isEqualTo(2);
        assertThat(router.secondarySize()).isEqualTo(1);
    }

    @Test
    void rollbackClearsSecondary() {
        VectorStore primary = new VectorStore();
        VectorStore secondary = new VectorStore();
        VectorDoubleWriteRouter router =
                new VectorDoubleWriteRouter(primary, secondary);
        router.beginMigration();
        router.put(new Embedding("a", new float[]{1, 0}));
        router.rollback();
        assertThat(router.secondarySize()).isZero();
        assertThat(router.migrating()).isFalse();
    }

    @Test
    void deleteDuringMigrationRemovesBoth() {
        VectorStore primary = new VectorStore();
        VectorStore secondary = new VectorStore();
        VectorDoubleWriteRouter router =
                new VectorDoubleWriteRouter(primary, secondary);
        router.beginMigration();
        router.put(new Embedding("a", new float[]{1, 0}));
        assertThat(router.delete("a")).isTrue();
        assertThat(router.primarySize()).isZero();
        assertThat(router.secondarySize()).isZero();
    }

    @Test
    void searchMergesDuringMigration() {
        VectorStore primary = new VectorStore();
        VectorStore secondary = new VectorStore();
        VectorDoubleWriteRouter router =
                new VectorDoubleWriteRouter(primary, secondary);
        router.put(new Embedding("a", new float[]{1, 0}));
        router.beginMigration();
        router.put(new Embedding("b", new float[]{0, 1}));
        assertThat(router.search(new float[]{1, 0}, 2)).hasSize(2);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {5, 100})
    void parameterizedSearchVolume(int count) {
        VectorStore primary = new VectorStore();
        VectorStore secondary = new VectorStore();
        VectorDoubleWriteRouter router =
                new VectorDoubleWriteRouter(primary, secondary);
        router.beginMigration();
        for (int i = 0; i < count; i++) {
            router.put(new Embedding("e" + i,
                    new float[]{i % 5, 5 - i % 5}));
        }
        assertThat(router.search(new float[]{1, 1}, 5)).hasSize(5);
    }
}
