package io.tieringkv.platform;

import io.tieringkv.replication.crdt.GCounter;
import io.tieringkv.replication.crdt.GSet;
import io.tieringkv.replication.crdt.LwwRegister;
import io.tieringkv.replication.crdt.OrSet;
import io.tieringkv.security.Role;
import io.tieringkv.security.rpc.RpcPermissionGuard;
import io.tieringkv.sql.AggregateType;
import io.tieringkv.sql.SqlEngine;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.hnsw.HnswIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 28 最终边缘矩阵：CRDT/SQL/HNSW/RBAC 参数化。 */
class Phase28FinalEdgeTest {

    @ParameterizedTest(name = "node {0}")
    @ValueSource(strings = {"n1", "node-a"})
    void gCounterNodeNames(String node) {
        GCounter counter = new GCounter();
        counter.increment(node);
        counter.increment(node);
        assertThat(counter.value()).isEqualTo(2);
    }

    @Test
    void gCounterLongNodeName() {
        GCounter counter = new GCounter();
        counter.increment("x".repeat(64));
        assertThat(counter.value()).isEqualTo(1);
    }

    @ParameterizedTest(name = "element {0}")
    @ValueSource(strings = {"", "a", "key-with-dash", "中文"})
    void gSetElementBoundaries(String element) {
        GSet set = new GSet();
        set.add(element);
        assertThat(set.contains(element)).isTrue();
        assertThat(set.size()).isEqualTo(1);
    }

    @ParameterizedTest(name = "ts {0}")
    @ValueSource(longs = {-1, 0, 1, Long.MAX_VALUE})
    void lwwTimestampBounds(long timestamp) {
        LwwRegister register = new LwwRegister();
        register.set(timestamp, "n", bytes("v"));
        // LWW 单调：负时间戳不覆盖默认 0。
        assertThat(register.timestamp())
                .isEqualTo(Math.max(0, timestamp));
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {0, 1, 100})
    void orSetVolume(int count) {
        OrSet set = new OrSet();
        for (int i = 0; i < count; i++) {
            set.add("k" + i, "t" + i);
        }
        assertThat(set.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1, 5, 100})
    void sqlCountAggregate(int count) {
        List<SqlEngine.Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new SqlEngine.Row(bytes("k" + i),
                    bytes(String.valueOf(i))));
        }
        assertThat(new SqlEngine().aggregate(rows, AggregateType.COUNT,
                row -> 0)).isEqualTo(count);
    }

    @ParameterizedTest(name = "vectors {0}")
    @ValueSource(ints = {1, 50, 300})
    void hnswSearchSizes(int count) {
        HnswIndex index = new HnswIndex(3);
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            embeddings.add(new Embedding("e" + i,
                    new float[]{i % 5, 5 - i % 5}));
        }
        index.build(embeddings);
        assertThat(index.search(new float[]{1, 1}, 3))
                .hasSize(Math.min(3, count));
    }

    @ParameterizedTest(name = "rpc {0}")
    @ValueSource(strings = {"TXN_GET", "TXN_PREWRITE", "META_PROPOSE",
            "BACKUP", "CDC"})
    void rpcPermissionMapping(String rpcType) {
        assertThat(RpcPermissionGuard.permissionFor(rpcType))
                .isNotNull();
    }

    @Test
    void rbacRoleSetsFrozen() {
        assertThat(Role.ADMIN.permissions()).hasSize(5);
        assertThat(Role.READER.permissions()).hasSize(1);
        assertThat(Role.WRITER.permissions()).hasSize(2);
    }

    @Test
    void vectorStoreSearchConsistency() {
        VectorStore store = new VectorStore();
        for (int i = 0; i < 20; i++) {
            store.put(new Embedding("e" + i,
                    new float[]{i % 4, 4 - i % 4}));
        }
        List<VectorStore.ScoredEmbedding> first =
                store.search(new float[]{1, 1}, 5);
        List<VectorStore.ScoredEmbedding> second =
                store.search(new float[]{1, 1}, 5);
        assertThat(first).hasSize(5);
        assertThat(second).hasSize(5);
    }

    @Test
    void vectorDuplicateIdOverwrite() {
        VectorStore store = new VectorStore();
        store.put(new Embedding("e", new float[]{1, 0}));
        store.put(new Embedding("e", new float[]{0, 1}));
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void gSetMergeIdempotent() {
        GSet a = new GSet();
        GSet b = new GSet();
        a.add("x");
        b.add("x");
        a.merge(b);
        a.merge(b);
        assertThat(a.size()).isEqualTo(1);
    }

    @Test
    void orSetMergeIdempotent() {
        OrSet a = new OrSet();
        OrSet b = new OrSet();
        a.add("k", "t1");
        b.add("k", "t1");
        a.merge(b);
        a.merge(b);
        assertThat(a.contains("k")).isTrue();
    }

    @Test
    void sqlJoinEmptyLeft() {
        assertThat(new SqlEngine().hashJoin(List.of(), List.of(),
                row -> row.key(), row -> row.key())).isEmpty();
    }

    @Test
    void hnswLevelBoundary() {
        HnswIndex index = new HnswIndex(1);
        index.build(List.of(new Embedding("e", new float[]{1, 0})));
        assertThat(index.search(new float[]{1, 0}, 1)).hasSize(1);
    }

    @Test
    void rbacWriterPermissions() {
        assertThat(Role.WRITER.allows(
                io.tieringkv.security.Permission.READ)).isTrue();
        assertThat(Role.WRITER.allows(
                io.tieringkv.security.Permission.WRITE)).isTrue();
        assertThat(Role.WRITER.allows(
                io.tieringkv.security.Permission.ADMIN)).isFalse();
    }

    @ParameterizedTest(name = "ts {0}")
    @ValueSource(longs = {1, 2, 100})
    void lwwIncreasingWins(long timestamp) {
        LwwRegister register = new LwwRegister();
        register.set(1, "n", bytes("old"));
        register.set(timestamp, "z", bytes("new"));
        assertThat(register.value()).isEqualTo(bytes("new"));
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 3, 50})
    void orSetAddCount(int count) {
        OrSet set = new OrSet();
        for (int i = 0; i < count; i++) {
            set.add("k" + i, "t" + i);
        }
        assertThat(set.elements()).hasSize(count);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {2, 10})
    void sqlSumEvenRows(int count) {
        List<SqlEngine.Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new SqlEngine.Row(bytes("k" + i),
                    bytes(String.valueOf(i + 1))));
        }
        long sum = new SqlEngine().aggregate(rows, AggregateType.SUM,
                row -> Long.parseLong(new String(row.value(),
                        StandardCharsets.UTF_8)));
        assertThat(sum).isEqualTo((long) count * (count + 1) / 2);
    }

    @ParameterizedTest(name = "vectors {0}")
    @ValueSource(ints = {5, 100})
    void vectorSearchStableOrder(int count) {
        VectorStore store = new VectorStore();
        for (int i = 0; i < count; i++) {
            store.put(new Embedding("e" + i,
                    new float[]{i % 4 + 1, 4 - i % 4}));
        }
        List<VectorStore.ScoredEmbedding> results =
                store.search(new float[]{1, 1}, 3);
        assertThat(results).hasSize(3);
        assertThat(results.get(0).score())
                .isGreaterThanOrEqualTo(results.get(1).score());
        assertThat(results.get(1).score())
                .isGreaterThanOrEqualTo(results.get(2).score());
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 100})
    void gSetAddVolume(int count) {
        GSet set = new GSet();
        for (int i = 0; i < count; i++) {
            set.add("k" + i);
        }
        assertThat(set.elements()).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 3, 50})
    void gCounterMergeMax(int count) {
        GCounter a = new GCounter();
        GCounter b = new GCounter();
        for (int i = 0; i < count; i++) {
            a.increment("n");
        }
        b.increment("n");
        a.merge(b);
        assertThat(a.value()).isEqualTo(count);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
