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

/** 双向复制边缘（ADR-0114）：CRDT 参数矩阵与收敛组合。 */
class BidirectionalEdgeTest {

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {2, 3, 5})
    void gCounterMultiNodeMerge(int nodes) {
        List<GCounter> counters = new ArrayList<>();
        for (int i = 0; i < nodes; i++) {
            counters.add(new GCounter());
        }
        for (int i = 0; i < nodes; i++) {
            counters.get(i).increment("n" + i);
        }
        GCounter merged = new GCounter();
        for (GCounter counter : counters) {
            merged.merge(counter);
        }
        assertThat(merged.value()).isEqualTo(nodes);
        for (GCounter counter : counters) {
            counter.merge(merged);
            assertThat(counter.value()).isEqualTo(nodes);
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 100})
    void gSetMergeVolume(int count) {
        GSet a = new GSet();
        GSet b = new GSet();
        for (int i = 0; i < count; i++) {
            a.add("a" + i);
            b.add("b" + i);
        }
        a.merge(b);
        assertThat(a.size()).isEqualTo(count * 2);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 100})
    void orSetMergeVolume(int count) {
        OrSet a = new OrSet();
        OrSet b = new OrSet();
        for (int i = 0; i < count; i++) {
            a.add("k" + i, "t" + i);
            b.add("k" + i, "t" + i);
        }
        for (int i = 0; i < count; i++) {
            a.remove("k" + i, "t" + i);
        }
        a.merge(b);
        b.merge(a);
        assertThat(a.size()).isZero();
        assertThat(b.size()).isZero();
    }

    @ParameterizedTest(name = "ts {0}")
    @ValueSource(longs = {0, 1, Long.MAX_VALUE})
    void lwwTimestampBoundaries(long timestamp) {
        LwwRegister register = new LwwRegister();
        register.set(timestamp, "n1", bytes("v"));
        assertThat(register.timestamp()).isEqualTo(timestamp);
    }

    @Test
    void lwwNullValueAllowed() {
        LwwRegister register = new LwwRegister();
        register.set(1, "n1", null);
        assertThat(register.value()).isNull();
    }

    @Test
    void versionVectorIsolation() {
        VersionVector a = new VersionVector();
        VersionVector b = new VersionVector();
        a.observe("n1", 5);
        assertThat(b.version("n1")).isZero();
    }

    @Test
    void bidirectionalPeerFailureWriteStillLocal() {
        ReplicaSink failing = new ReplicaSink() {
            @Override
            public CompletableFuture<Void> apply(
                    io.tieringkv.cdc.ChangeEvent event) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("down"));
            }

            @Override
            public String replicaId() {
                return "r2";
            }
        };
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(failing), "r1", 50);
        assertThat(pipeline.write(bytes("k"), bytes("v")).join())
                .isFalse(); // SYNC 语义：peer 故障写入失败
        assertThat(pipeline.get(bytes("k"))).isEqualTo(bytes("v"));
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {2, 8, 32})
    void parameterizedConflictConvergence(int count) {
        RecordingSink aPeer = new RecordingSink("r2");
        RecordingSink bPeer = new RecordingSink("r1");
        BidirectionalPipeline a = new BidirectionalPipeline(
                List.of(aPeer), "r1", 2_000);
        BidirectionalPipeline b = new BidirectionalPipeline(
                List.of(bPeer), "r2", 2_000);
        for (int i = 0; i < count; i++) {
            a.write(bytes("k" + i), bytes("a")).join();
            b.write(bytes("k" + i), bytes("b")).join();
            a.receive(bytes("k" + i), bytes("b"), "r2", i + 1);
            b.receive(bytes("k" + i), bytes("a"), "r1", i + 1);
        }
        for (int i = 0; i < count; i++) {
            assertThat(a.get(bytes("k" + i))).isEqualTo(bytes("b"));
            assertThat(b.get(bytes("k" + i))).isEqualTo(bytes("b"));
        }
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
