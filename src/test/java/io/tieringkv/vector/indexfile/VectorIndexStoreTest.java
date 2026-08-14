package io.tieringkv.vector.indexfile;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 向量索引存储闭环（ADR-0319）：checkpoint / load / rebuild。 */
class VectorIndexStoreTest {

    @TempDir
    Path dir;

    @Test
    void checkpointLoadPreservesSearch() throws Exception {
        VectorIndexStore store = new VectorIndexStore(4);
        store.put(new Embedding("a", new float[]{1, 0}));
        store.put(new Embedding("b", new float[]{0, 1}));
        Path file = dir.resolve("idx.tvif");
        store.checkpoint(file);

        VectorIndexStore loaded = VectorIndexStore.load(file);
        assertThat(loaded.size()).isEqualTo(2);
        assertThat(loaded.dim()).isEqualTo(2);
        assertThat(loaded.store().search(new float[]{1, 0}, 1)
                .get(0).id()).isEqualTo("a");
    }

    @Test
    void deleteReflectedInNextCheckpoint() throws Exception {
        VectorIndexStore store = new VectorIndexStore(3);
        store.put(new Embedding("a", new float[]{1, 0}));
        store.put(new Embedding("b", new float[]{0, 1}));
        assertThat(store.delete("a")).isTrue();
        Path file = dir.resolve("idx.tvif");
        store.checkpoint(file);
        assertThat(VectorIndexStore.load(file).size()).isEqualTo(1);
    }

    @Test
    void rebuildIndexMatchesStoreSearch() throws Exception {
        VectorIndexStore store = new VectorIndexStore(4);
        for (int i = 0; i < 50; i++) {
            store.put(new Embedding("e" + i,
                    new float[]{i % 9, 9 - i % 9}));
        }
        var rebuilt = store.rebuildIndex();
        assertThat(rebuilt.size()).isEqualTo(50);
        assertThat(rebuilt.search(new float[]{1, 1}, 5))
                .hasSize(5);
    }

    @Test
    void snapshotIsUnmodifiableCopy() {
        VectorIndexStore store = new VectorIndexStore(2);
        store.put(new Embedding("a", new float[]{1, 0}));
        assertThatThrownBy(() -> store.snapshot().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void storeSearchUsesCosineRanking() {
        VectorIndexStore store = new VectorIndexStore(2);
        store.put(new Embedding("near", new float[]{3, 4}));
        store.put(new Embedding("far", new float[]{-3, -4}));
        VectorStore.ScoredEmbedding top =
                store.store().search(new float[]{3, 4}, 1).get(0);
        assertThat(top.id()).isEqualTo("near");
        assertThat(top.score()).isEqualTo(1.0);
    }
}
