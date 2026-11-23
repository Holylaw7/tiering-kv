package io.tieringkv.replication;

import io.tieringkv.replication.crdt.GCounter;
import io.tieringkv.replication.crdt.GSet;
import io.tieringkv.replication.crdt.LwwRegister;
import io.tieringkv.replication.crdt.OrSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 28 CRDT/复制参数矩阵（ADR-0114）。 */
class Phase28CrdtEdgeTest {

    @ParameterizedTest(name = "nodes {0} increments {1}")
    @ValueSource(ints = {1, 5})
    void gCounterMergeConvergesAllOrders(int nodes) {
        List<GCounter> counters = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            GCounter counter = new GCounter();
            counter.increment("n" + i);
            counter.increment("n" + i);
            counters.add(counter);
        }
        for (int i = 0; i < nodes; i++) {
            for (int j = 0; j < nodes; j++) {
                counters.get(i).merge(counters.get(j));
            }
        }
        for (GCounter counter : counters) {
            assertThat(counter.value()).isEqualTo(nodes * 2L);
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {0, 1, 500})
    void gSetEmptyAndVolume(int count) {
        GSet a = new GSet();
        GSet b = new GSet();
        for (int i = 0; i < count; i++) {
            a.add("k" + i);
        }
        a.merge(b);
        assertThat(a.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 100})
    void orSetConcurrentAddRemove(int count) {
        OrSet a = new OrSet();
        OrSet b = new OrSet();
        for (int i = 0; i < count; i++) {
            a.add("k" + i, "a" + i);
            b.add("k" + i, "b" + i);
        }
        a.remove("k0", "a0");
        b.remove("k0", "b0");
        a.merge(b);
        b.merge(a);
        assertThat(a.contains("k0")).isFalse();
        assertThat(a.size()).isEqualTo(count - 1);
    }

    @ParameterizedTest(name = "ts {0} node {1}")
    @ValueSource(longs = {1, 10})
    void lwwMergeIdempotent(long timestamp) {
        LwwRegister a = new LwwRegister();
        LwwRegister b = new LwwRegister();
        a.set(timestamp, "n1", bytes("x"));
        b.set(timestamp, "n2", bytes("y"));
        a.merge(b);
        a.merge(b);
        assertThat(a.value()).isEqualTo(bytes("y"));
    }

    @Test
    void versionVectorMultiNode() {
        VersionVector vector = new VersionVector();
        for (int i = 0; i < 5; i++) {
            vector.observe("n" + i, i + 1);
        }
        assertThat(vector.snapshot()).hasSize(5);
        assertThat(vector.version("n4")).isEqualTo(5);
    }

    @Test
    void bidirectionalStaleRemoteIgnored() {
        RecordingSink peer = new RecordingSink("r2");
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(peer), "r1", 1_000);
        pipeline.write(bytes("k"), bytes("v2")).join();
        pipeline.receive(bytes("k"), bytes("v1"), "r2", 0);
        assertThat(pipeline.get(bytes("k"))).isEqualTo(bytes("v2"));
    }

    @ParameterizedTest(name = "writes {0}")
    @ValueSource(ints = {2, 20})
    void bidirectionalVersionVectorGrows(int writes) {
        RecordingSink peer = new RecordingSink("r2");
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(peer), "r1", 2_000);
        for (int i = 0; i < writes; i++) {
            pipeline.write(bytes("k" + i), bytes("v" + i)).join();
        }
        assertThat(pipeline.vector().version("r1"))
                .isEqualTo(writes);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class RecordingSink implements ReplicaSink {
        private final String id;

        private RecordingSink(String id) {
            this.id = id;
        }

        @Override
        public CompletableFuture<Void> apply(
                io.tieringkv.cdc.ChangeEvent event) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public String replicaId() {
            return id;
        }
    }
}
