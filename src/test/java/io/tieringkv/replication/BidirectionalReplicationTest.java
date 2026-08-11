package io.tieringkv.replication;

import io.tieringkv.cdc.ChangeEvent;
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

/** 双向复制与 CRDT（ADR-0114）：收敛、环回抑制、冲突解决。 */
class BidirectionalReplicationTest {

    @Test
    void lwwLaterTimestampWins() {
        LwwRegister register = new LwwRegister();
        register.set(1, "a", bytes("v1"));
        register.set(2, "b", bytes("v2"));
        assertThat(register.value()).isEqualTo(bytes("v2"));
    }

    @Test
    void lwwTieBreaksByNode() {
        LwwRegister register = new LwwRegister();
        register.set(5, "b", bytes("from-b"));
        register.set(5, "a", bytes("from-a"));
        assertThat(register.value()).isEqualTo(bytes("from-b"));
    }

    @Test
    void lwwMergeConverges() {
        LwwRegister a = new LwwRegister();
        LwwRegister b = new LwwRegister();
        a.set(10, "n1", bytes("x"));
        b.set(20, "n2", bytes("y"));
        a.merge(b);
        b.merge(a);
        assertThat(a.value()).isEqualTo(bytes("y"));
        assertThat(b.value()).isEqualTo(bytes("y"));
    }

    @Test
    void gCounterIncrementAndMerge() {
        GCounter a = new GCounter();
        GCounter b = new GCounter();
        a.increment("n1");
        a.increment("n1");
        b.increment("n2");
        a.merge(b);
        b.merge(a);
        assertThat(a.value()).isEqualTo(3);
        assertThat(b.value()).isEqualTo(3);
    }

    @Test
    void gCounterMergeTakesMaxPerNode() {
        GCounter a = new GCounter();
        GCounter b = new GCounter();
        a.increment("n1");
        a.increment("n1");
        b.increment("n1");
        a.merge(b);
        assertThat(a.value()).isEqualTo(2);
    }

    @Test
    void gSetUnionMerge() {
        GSet a = new GSet();
        GSet b = new GSet();
        a.add("x");
        b.add("y");
        a.merge(b);
        assertThat(a.contains("x")).isTrue();
        assertThat(a.contains("y")).isTrue();
        assertThat(a.size()).isEqualTo(2);
    }

    @Test
    void orSetAddRemoveMergeConverges() {
        OrSet a = new OrSet();
        OrSet b = new OrSet();
        a.add("k", "t1");
        b.add("k", "t1");
        a.remove("k", "t1");
        b.remove("k", "t1");
        a.merge(b);
        b.merge(a);
        assertThat(a.contains("k")).isFalse();
        assertThat(b.contains("k")).isFalse();
    }

    @Test
    void orSetReAddAfterRemoveVisible() {
        OrSet set = new OrSet();
        set.add("k", "t1");
        set.remove("k", "t2");
        set.add("k", "t3");
        assertThat(set.contains("k")).isTrue();
    }

    @Test
    void versionVectorTracksMaxPerNode() {
        VersionVector vector = new VersionVector();
        vector.observe("n1", 5);
        vector.observe("n1", 3);
        assertThat(vector.version("n1")).isEqualTo(5);
    }

    @Test
    void versionVectorSeen() {
        VersionVector vector = new VersionVector();
        vector.observe("n1", 5);
        assertThat(vector.seen("n1", 4)).isTrue();
        assertThat(vector.seen("n1", 5)).isTrue();
        assertThat(vector.seen("n1", 6)).isFalse();
    }

    @Test
    void versionVectorMerge() {
        VersionVector a = new VersionVector();
        VersionVector b = new VersionVector();
        a.observe("n1", 5);
        b.observe("n2", 7);
        a.merge(b);
        assertThat(a.version("n1")).isEqualTo(5);
        assertThat(a.version("n2")).isEqualTo(7);
    }

    @Test
    void bidirectionalWriteBroadcastsToPeers() {
        RecordingSink peer = new RecordingSink("r2");
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(peer), "r1", 2_000);
        pipeline.write(bytes("k"), bytes("v")).join();
        assertThat(peer.events()).hasSize(1);
        assertThat(pipeline.get(bytes("k"))).isEqualTo(bytes("v"));
    }

    @Test
    void bidirectionalDuplicateReceiveSuppressed() {
        RecordingSink peer = new RecordingSink("r2");
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(peer), "r1", 2_000);
        pipeline.write(bytes("k"), bytes("v")).join();
        pipeline.receive(bytes("k"), bytes("v"), "r1", 1);
        pipeline.receive(bytes("k"), bytes("v"), "r1", 1);
        assertThat(pipeline.suppressedCount()).isEqualTo(2);
    }

    @Test
    void bidirectionalRemoteWriteWinsByVersion() {
        RecordingSink peer = new RecordingSink("r2");
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(peer), "r1", 2_000);
        pipeline.write(bytes("k"), bytes("local")).join();
        pipeline.receive(bytes("k"), bytes("remote"), "r2", 10);
        assertThat(pipeline.get(bytes("k"))).isEqualTo(bytes("remote"));
    }

    @Test
    void bidirectionalConcurrentConflictConverges() {
        RecordingSink aPeer = new RecordingSink("r2");
        RecordingSink bPeer = new RecordingSink("r1");
        BidirectionalPipeline a = new BidirectionalPipeline(
                List.of(aPeer), "r1", 2_000);
        BidirectionalPipeline b = new BidirectionalPipeline(
                List.of(bPeer), "r2", 2_000);
        a.write(bytes("k"), bytes("va")).join();
        b.write(bytes("k"), bytes("vb")).join();
        a.receive(bytes("k"), bytes("vb"), "r2", 1);
        b.receive(bytes("k"), bytes("va"), "r1", 1);
        assertThat(a.get(bytes("k"))).isEqualTo(bytes("vb"));
        assertThat(b.get(bytes("k"))).isEqualTo(bytes("vb"));
    }

    @Test
    void bidirectionalConflictCounted() {
        RecordingSink peer = new RecordingSink("r2");
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(peer), "r1", 2_000);
        pipeline.write(bytes("k"), bytes("v1")).join();
        pipeline.receive(bytes("k"), bytes("v2"), "r2", 10);
        assertThat(pipeline.conflictsCount()).isEqualTo(1);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedBidirectionalWrites(int count) {
        RecordingSink peer = new RecordingSink("r2");
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(peer), "r1", 5_000);
        for (int i = 0; i < count; i++) {
            pipeline.write(bytes("k" + i), bytes("v" + i)).join();
        }
        assertThat(peer.events()).hasSize(count);
        assertThat(pipeline.get(bytes("k" + (count - 1))))
                .isEqualTo(bytes("v" + (count - 1)));
    }

    @Test
    void noPeersWriteSucceeds() {
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(), "r1", 1_000);
        assertThat(pipeline.write(bytes("k"), bytes("v")).join())
                .isTrue();
    }

    @ParameterizedTest(name = "size {0}")
    @ValueSource(ints = {0, 1, 4096})
    void parameterizedValueSizes(int size) {
        RecordingSink peer = new RecordingSink("r2");
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(peer), "r1", 2_000);
        pipeline.write(bytes("k"), new byte[size]).join();
        assertThat(pipeline.get(bytes("k"))).hasSize(size);
    }

    @Test
    void versionVectorExposed() {
        RecordingSink peer = new RecordingSink("r2");
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(peer), "r1", 2_000);
        pipeline.write(bytes("k"), bytes("v")).join();
        assertThat(pipeline.vector().version("r1")).isEqualTo(1);
    }

    @Test
    void staleRemoteWriteIgnored() {
        RecordingSink peer = new RecordingSink("r2");
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(peer), "r1", 2_000);
        pipeline.write(bytes("k"), bytes("v2")).join();
        pipeline.receive(bytes("k"), bytes("v1"), "r2", 0);
        assertThat(pipeline.get(bytes("k"))).isEqualTo(bytes("v2"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class RecordingSink implements ReplicaSink {
        private final String id;
        private final List<ChangeEvent> events = new ArrayList<>();

        private RecordingSink(String id) {
            this.id = id;
        }

        @Override
        public synchronized CompletableFuture<Void> apply(
                ChangeEvent event) {
            events.add(event);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public String replicaId() {
            return id;
        }

        synchronized List<ChangeEvent> events() {
            return List.copyOf(events);
        }
    }
}
