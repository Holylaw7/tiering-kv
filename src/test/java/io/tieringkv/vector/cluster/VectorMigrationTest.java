package io.tieringkv.vector.cluster;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 向量分片迁移（ADR-0127）：逐 id 迁移 + 校验。 */
class VectorMigrationTest {

    @Test
    void migrateMovesEmbedding() {
        VectorStore source = new VectorStore();
        VectorStore target = new VectorStore();
        Embedding embedding = new Embedding("a", new float[]{1, 0});
        source.put(embedding);
        ShardMigrationExecutor executor =
                new ShardMigrationExecutor(source, target);
        executor.migrate("a", embedding);
        assertThat(source.size()).isZero();
        assertThat(target.size()).isEqualTo(1);
        assertThat(executor.verify()).isTrue();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 50, 200})
    void parameterizedMigration(int count) {
        VectorStore source = new VectorStore();
        VectorStore target = new VectorStore();
        ShardMigrationExecutor executor =
                new ShardMigrationExecutor(source, target);
        for (int i = 0; i < count; i++) {
            Embedding embedding = new Embedding("e" + i,
                    new float[]{i % 3, 3 - i % 3});
            source.put(embedding);
            executor.migrate("e" + i, embedding);
        }
        assertThat(executor.verify()).isTrue();
        assertThat(target.size()).isEqualTo(count);
    }

    @Test
    void verifyFailsWhenSourceRemains() {
        VectorStore source = new VectorStore();
        source.put(new Embedding("a", new float[]{1, 0}));
        ShardMigrationExecutor executor =
                new ShardMigrationExecutor(source, new VectorStore());
        assertThat(executor.verify()).isFalse();
    }

    @Test
    void searchRecoversAfterMigration() {
        VectorStore source = new VectorStore();
        VectorStore target = new VectorStore();
        ShardMigrationExecutor executor =
                new ShardMigrationExecutor(source, target);
        Embedding near = new Embedding("near", new float[]{1, 0});
        source.put(near);
        executor.migrate("near", near);
        assertThat(target.search(new float[]{1, 0}, 1).get(0).id())
                .isEqualTo("near");
    }

    @Test
    void deleteAfterMigrateNoop() {
        VectorStore source = new VectorStore();
        VectorStore target = new VectorStore();
        ShardMigrationExecutor executor =
                new ShardMigrationExecutor(source, target);
        Embedding embedding = new Embedding("a", new float[]{1, 0});
        executor.migrate("a", embedding);
        assertThat(source.delete("a")).isFalse();
    }
}
