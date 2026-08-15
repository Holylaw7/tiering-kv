package io.tieringkv.vector.collection;

import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 向量多集合命名空间（ADR-0338）：隔离、命令、自动 checkpoint。 */
class VectorCollectionTest {

    @TempDir
    Path dir;

    private TestCommandRunner runner(VectorCollectionRegistry registry) {
        return new TestCommandRunner(MemTable.create(),
                CommandRegistry.createDefaultWithVector(
                        () -> "# Server\r\nno metrics\r\n",
                        Map.of(), registry));
    }

    private static String text(RespValue value) {
        return new String(((RespBulkString) value).bytes(),
                StandardCharsets.UTF_8);
    }

    private static long integer(RespValue value) {
        return ((RespInteger) value).value();
    }

    @Test
    void collectionsAreIsolated() {
        VectorCollectionRegistry registry = new VectorCollectionRegistry();
        registry.put("a", new Embedding("x", new float[]{1, 0}));
        registry.put("b", new Embedding("x", new float[]{0, 1}));
        List<VectorStore.ScoredEmbedding> inA =
                registry.collection("a").store()
                        .search(new float[]{1, 0}, 1);
        List<VectorStore.ScoredEmbedding> inB =
                registry.collection("b").store()
                        .search(new float[]{1, 0}, 1);
        assertThat(inA.get(0).id()).isEqualTo("x");
        assertThat(inA.get(0).score()).isEqualTo(1.0);
        assertThat(inB.get(0).score()).isEqualTo(0.0);
    }

    @Test
    void defaultCollectionWrapsExistingStore() {
        VectorIndexStore store = new VectorIndexStore(4);
        VectorCollectionRegistry registry =
                VectorCollectionRegistry.ofDefault(store);
        assertThat(registry.collection("default"))
                .isSameAs(store);
        registry.put("default", new Embedding("x",
                new float[]{1, 0}));
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void collectionAutoCreatedAndListedSorted() {
        VectorCollectionRegistry registry = new VectorCollectionRegistry();
        registry.put("b", new Embedding("x", new float[]{1, 0}));
        registry.put("a", new Embedding("y", new float[]{0, 1}));
        assertThat(registry.names())
                .containsExactly("a", "b");
        assertThat(registry.hasCollection("a")).isTrue();
    }

    @Test
    void dropRemovesCollection() {
        VectorCollectionRegistry registry = new VectorCollectionRegistry();
        registry.put("a", new Embedding("x", new float[]{1, 0}));
        assertThat(registry.drop("a")).isTrue();
        assertThat(registry.drop("a")).isFalse();
        assertThat(registry.hasCollection("a")).isFalse();
    }

    @Test
    void checkpointAndLoadAllRoundTrip() throws Exception {
        VectorCollectionRegistry registry = new VectorCollectionRegistry();
        registry.put("docs", new Embedding("d1", new float[]{1, 0}));
        registry.put("docs", new Embedding("d2", new float[]{0, 1}));
        registry.put("audit", new Embedding("a1", new float[]{1, 1}));
        registry.checkpointAll(dir);

        assertThat(Files.exists(dir.resolve("docs.tvif"))).isTrue();
        VectorCollectionRegistry restored =
                VectorCollectionRegistry.loadAll(dir);
        assertThat(restored.names())
                .containsExactly("audit", "docs");
        assertThat(restored.collection("docs").size()).isEqualTo(2);
        assertThat(restored.collection("audit").size())
                .isEqualTo(1);
        assertThat(restored.collection("docs").store()
                .search(new float[]{1, 0}, 1).get(0).id())
                .isEqualTo("d1");
    }

    @Test
    void autoCheckpointFlushesDirtyAndCloseFlushesAll()
            throws Exception {
        VectorCollectionRegistry registry = new VectorCollectionRegistry();
        registry.configureCheckpoint(dir);
        registry.startAutoCheckpoint(50);
        registry.put("docs", new Embedding("d1", new float[]{1, 0}));
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(3);
        boolean flushed = false;
        while (System.nanoTime() < deadline) {
            if (Files.exists(dir.resolve("docs.tvif"))) {
                flushed = true;
                break;
            }
            Thread.sleep(25);
        }
        assertThat(flushed).isTrue();

        registry.put("audit", new Embedding("a1", new float[]{1, 1}));
        registry.close();
        assertThat(Files.exists(dir.resolve("audit.tvif"))).isTrue();
        assertThat(VectorCollectionRegistry.loadAll(dir)
                .collection("audit").size()).isEqualTo(1);
    }

    @Test
    void vectorCommandsSupportCollectionPrefix() {
        VectorCollectionRegistry registry = new VectorCollectionRegistry();
        TestCommandRunner runner = runner(registry);
        runner.exec("vector.add", "collection", "docs",
                "d1", "2", "1", "0");
        RespArray result = (RespArray) runner.exec("vector.search",
                "collection", "docs", "2", "1", "0", "topk", "1");
        assertThat(result.values()).hasSize(1);
        assertThat(text(((RespArray) result.values().get(0))
                .values().get(0))).isEqualTo("d1");
        assertThat(integer(runner.exec("vector.len",
                "collection", "docs"))).isEqualTo(1);
        assertThat(integer(runner.exec("vector.del",
                "collection", "docs", "d1"))).isEqualTo(1);
        assertThat(registry.collection("docs").size()).isZero();
    }

    @Test
    void vectorCommandsWithoutCollectionUseDefault() {
        VectorCollectionRegistry registry = new VectorCollectionRegistry();
        TestCommandRunner runner = runner(registry);
        runner.exec("vector.add", "x", "2", "1", "0");
        assertThat(integer(runner.exec("vector.len")))
                .isEqualTo(1);
        assertThat(registry.collection("default").size())
                .isEqualTo(1);
    }

    @Test
    void vectorSearchMissingCollectionReturnsEmpty() {
        VectorCollectionRegistry registry = new VectorCollectionRegistry();
        TestCommandRunner runner = runner(registry);
        RespArray result = (RespArray) runner.exec("vector.search",
                "collection", "nope", "2", "1", "0");
        assertThat(result.values()).isEmpty();
    }

    @Test
    void vectorListSortedAndDrop() {
        VectorCollectionRegistry registry = new VectorCollectionRegistry();
        TestCommandRunner runner = runner(registry);
        runner.exec("vector.add", "collection", "c2", "x",
                "2", "1", "0");
        runner.exec("vector.add", "collection", "c1", "y",
                "2", "0", "1");
        RespArray list = (RespArray) runner.exec("vector.list");
        assertThat(list.values()).hasSize(2);
        assertThat(text(((RespArray) list.values().get(0))
                .values().get(0))).isEqualTo("c1");
        assertThat(integer(((RespArray) list.values().get(1))
                .values().get(1))).isEqualTo(1);
        assertThat(integer(runner.exec("vector.drop", "c1")))
                .isEqualTo(1);
        assertThat(integer(runner.exec("vector.drop", "c1")))
                .isZero();
    }

    @Test
    void vectorCheckpointCommandRequiresConfiguredDir() {
        VectorCollectionRegistry registry = new VectorCollectionRegistry();
        TestCommandRunner runner = runner(registry);
        assertThat(runner.exec("vector.checkpoint"))
                .isInstanceOf(RespError.class);
        registry.configureCheckpoint(dir);
        runner.exec("vector.add", "collection", "docs",
                "d1", "2", "1", "0");
        assertThat(runner.exec("vector.checkpoint"))
                .isEqualTo(new RespSimpleString("OK"));
        assertThat(Files.exists(dir.resolve("docs.tvif"))).isTrue();
    }
}
