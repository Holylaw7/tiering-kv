package io.tieringkv.storage.memory;

import io.tieringkv.storage.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 批量写（ADR-0048）：原子应用 / 版本顺序 / TTL / 内存记账。 */
class MemTableBatchWriteTest {

    private static final MutableClock CLOCK = new MutableClock(0);

    private static MemTable table() {
        return MemTable.createForTest(CLOCK, new MemoryManager(1 << 30));
    }

    @Test
    void applyBatchPutsAll() {
        MemTable table = table();
        int applied = table.applyBatch(BatchWriteRequest.of(
                Mutation.put(key("a"), value("1")),
                Mutation.put(key("b"), value("2"))));
        assertThat(applied).isEqualTo(2);
        assertThat(table.get(key("a"))).isEqualTo(value("1"));
        assertThat(table.get(key("b"))).isEqualTo(value("2"));
        assertThat(table.size()).isEqualTo(2);
    }

    @Test
    void applyBatchDeletesAll() {
        MemTable table = table();
        table.put(key("a"), value("1"));
        table.put(key("b"), value("2"));
        int applied = table.applyBatch(BatchWriteRequest.of(
                Mutation.delete(key("a")), Mutation.delete(key("b"))));
        assertThat(applied).isEqualTo(2);
        assertThat(table.get(key("a"))).isNull();
        assertThat(table.get(key("b"))).isNull();
    }

    @Test
    void emptyBatchRejected() {
        MemTable table = table();
        assertThatThrownBy(() -> table.applyBatch(
                new BatchWriteRequest(List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void batchTooLargeRejected() {
        MemTable table = table();
        List<Mutation> mutations = new ArrayList<>();
        for (int i = 0; i <= BatchWriteRequest.MAX_BATCH_SIZE; i++) {
            mutations.add(Mutation.put(key("k" + i), value("v")));
        }
        assertThatThrownBy(() -> table.applyBatch(new BatchWriteRequest(mutations)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mixedPutDeleteOrder() {
        MemTable table = table();
        table.put(key("x"), value("old"));
        table.applyBatch(BatchWriteRequest.of(
                Mutation.put(key("x"), value("new")),
                Mutation.delete(key("x"))));
        assertThat(table.get(key("x"))).isNull();
    }

    @Test
    void versionOrderingIsSequential() {
        MemTable table = table();
        table.applyBatch(BatchWriteRequest.of(
                Mutation.put(key("a"), value("1")),
                Mutation.put(key("b"), value("2")),
                Mutation.put(key("c"), value("3"))));
        long v1 = table.getEntry(key("a")).version();
        long v2 = table.getEntry(key("b")).version();
        long v3 = table.getEntry(key("c")).version();
        assertThat(v1).isLessThan(v2);
        assertThat(v2).isLessThan(v3);
    }

    @Test
    void batchTtlApplied() throws Exception {
        MutableClock clock = new MutableClock(0);
        MemTable table = MemTable.createForTest(clock, new MemoryManager(1 << 30));
        table.applyBatch(BatchWriteRequest.of(
                Mutation.put(key("exp"), value("v"), 100)));
        assertThat(table.get(key("exp"))).isEqualTo(value("v"));
        clock.advance(101);
        assertThat(table.get(key("exp"))).isNull();
    }

    @Test
    void batchTtlZeroDeletes() {
        MemTable table = table();
        table.put(key("z"), value("v"));
        table.applyBatch(BatchWriteRequest.of(
                Mutation.put(key("z"), value("v2"), 0)));
        assertThat(table.get(key("z"))).isNull();
    }

    @Test
    void emptyKeyRejectedAtomically() {
        MemTable table = table();
        assertThatThrownBy(() -> table.applyBatch(BatchWriteRequest.of(
                Mutation.put(key("ok"), value("v")),
                Mutation.put(new byte[0], value("bad")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(table.get(key("ok"))).isNull();
    }

    @Test
    void nullValueRejectedAtomically() {
        MemTable table = table();
        assertThatThrownBy(() -> table.applyBatch(BatchWriteRequest.of(
                Mutation.put(key("ok"), value("v")),
                new Mutation(Mutation.Type.PUT, key("bad"), null, -1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(table.get(key("ok"))).isNull();
    }

    @Test
    void batchOverwritesExisting() {
        MemTable table = table();
        table.put(key("k"), value("old"));
        table.applyBatch(BatchWriteRequest.of(Mutation.put(key("k"), value("new"))));
        assertThat(table.get(key("k"))).isEqualTo(value("new"));
        assertThat(table.size()).isEqualTo(1);
    }

    @Test
    void deleteNonExistentIsNoOp() {
        MemTable table = table();
        int applied = table.applyBatch(BatchWriteRequest.of(Mutation.delete(key("none"))));
        assertThat(applied).isEqualTo(1);
        assertThat(table.size()).isZero();
    }

    @Test
    void largeBatchScale() {
        MemTable table = table();
        List<Mutation> mutations = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            mutations.add(Mutation.put(key("k" + i), value("v")));
        }
        int applied = 0;
        for (int i = 0; i < mutations.size(); i += 1000) {
            applied += table.applyBatch(new BatchWriteRequest(
                    mutations.subList(i, Math.min(i + 1000, mutations.size()))));
        }
        assertThat(applied).isEqualTo(100_000);
        assertThat(table.size()).isEqualTo(100_000);
        assertThat(table.get(key("k99999"))).isEqualTo(value("v"));
    }

    @Test
    void batchPreservesValues() {
        MemTable table = table();
        byte[] large = new byte[4096];
        java.util.Arrays.fill(large, (byte) 9);
        table.applyBatch(BatchWriteRequest.of(Mutation.put(key("big"), large)));
        assertThat(table.get(key("big"))).isEqualTo(large);
    }

    @Test
    void deleteThenPutSameKeyWins() {
        MemTable table = table();
        table.put(key("k"), value("old"));
        table.applyBatch(BatchWriteRequest.of(
                Mutation.delete(key("k")),
                Mutation.put(key("k"), value("final"))));
        assertThat(table.get(key("k"))).isEqualTo(value("final"));
        assertThat(table.size()).isEqualTo(1);
    }

    @Test
    void mutationRoundTripCloneSafety() {
        byte[] key = key("k");
        byte[] value = value("v");
        Mutation mutation = Mutation.put(key, value);
        key[0] = 'X';
        value[0] = 'Y';
        assertThat(mutation.key()).isEqualTo(key("k"));
        assertThat(mutation.value()).isEqualTo(value("v"));
    }

    @Test
    void batchWriteRequestImmutability() {
        List<Mutation> source = new ArrayList<>();
        source.add(Mutation.put(key("a"), value("1")));
        BatchWriteRequest request = new BatchWriteRequest(source);
        source.clear();
        assertThat(request.mutations()).hasSize(1);
        assertThatThrownBy(() -> request.mutations().add(Mutation.delete(key("b"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void concurrentBatchApplyWithReads() throws Exception {
        MemTable table = table();
        for (int i = 0; i < 1000; i++) {
            table.put(key("pre" + i), value("v"));
        }
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                table.applyBatch(BatchWriteRequest.of(
                        Mutation.put(key("w" + i), value("v")),
                        Mutation.delete(key("pre" + i))));
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                table.get(key("pre" + i));
                table.get(key("w" + (i % 100)));
            }
        });
        writer.start();
        reader.start();
        writer.join(5000);
        reader.join(5000);
        assertThat(writer.isAlive()).isFalse();
        assertThat(table.size()).isEqualTo(900 + 100);
    }

    @Test
    void batchMemoryAccounting() {
        MemTable table = table();
        table.applyBatch(BatchWriteRequest.of(
                Mutation.put(key("a"), value("1")),
                Mutation.put(key("b"), value("2"))));
        table.applyBatch(BatchWriteRequest.of(Mutation.delete(key("a"))));
        assertThat(table.getEntry(key("a")).deleted()).isTrue();
        assertThat(table.size()).isEqualTo(1);
    }

    @Test
    void batchSameKeyMultipleSegmentsOrdered() {
        MemTable table = table();
        table.applyBatch(BatchWriteRequest.of(
                Mutation.put(key("a"), value("1")),
                Mutation.put(key("b"), value("2")),
                Mutation.put(key("c"), value("3"))));
        assertThat(table.get(key("a"))).isEqualTo(value("1"));
        assertThat(table.get(key("b"))).isEqualTo(value("2"));
        assertThat(table.get(key("c"))).isEqualTo(value("3"));
    }

    @Test
    void batchWithTtlExpiresViaActiveExpire() {
        MutableClock clock = new MutableClock(0);
        MemTable table = MemTable.createForTest(clock, new MemoryManager(1 << 30));
        table.applyBatch(BatchWriteRequest.of(
                Mutation.put(key("t"), value("v"), 50)));
        clock.advance(51);
        table.activeExpire();
        assertThat(table.get(key("t"))).isNull();
    }

    private static byte[] key(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
