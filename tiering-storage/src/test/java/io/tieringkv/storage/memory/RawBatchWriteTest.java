package io.tieringkv.storage.memory;

import io.tieringkv.storage.StorageIterator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 零拷贝批量写（ADR-0059）：所有权转移 / 版本顺序 / TTL / 并发。 */
class RawBatchWriteTest {

    @Test
    void applyRawBatchStoresAllEntries() {
        MemTable table = MemTable.create();
        try {
            table.applyRawBatch(rawBatch(0, 100));
            assertThat(table.size()).isEqualTo(100);
        } finally {
            table.close();
        }
    }

    @Test
    void applyRawBatchValuesReadable() {
        MemTable table = MemTable.create();
        try {
            table.applyRawBatch(List.of(RawMutation.of(
                    bytes("k"), bytes("v"), 1, -1)));
            assertThat(table.get(bytes("k"))).isEqualTo(bytes("v"));
        } finally {
            table.close();
        }
    }

    @Test
    void emptyBatchRejected() {
        MemTable table = MemTable.create();
        try {
            assertThatThrownBy(() -> table.applyRawBatch(List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            table.close();
        }
    }

    @Test
    void nullValueRejected() {
        MemTable table = MemTable.create();
        try {
            assertThatThrownBy(() -> table.applyRawBatch(List.of(
                    RawMutation.of(bytes("k"), null, 1, -1))))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            table.close();
        }
    }

    @Test
    void emptyKeyRejected() {
        MemTable table = MemTable.create();
        try {
            assertThatThrownBy(() -> table.applyRawBatch(List.of(
                    RawMutation.of(new byte[0], bytes("v"), 1, -1))))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            table.close();
        }
    }

    @Test
    void versionsMatchBatchOrder() {
        MemTable table = MemTable.create();
        try {
            table.applyRawBatch(rawBatch(0, 5));
            for (int i = 0; i < 5; i++) {
                assertThat(table.getEntry(bytes("k" + i)).version())
                        .isEqualTo(i + 1L); // 版本计数器从 1 开始
            }
        } finally {
            table.close();
        }
    }

    @Test
    void ttlScheduledOnEntry() {
        AtomicLong clock = new AtomicLong(1_000);
        MemTable table = MemTable.createForTest(() -> clock.get(),
                new MemoryManager(Long.MAX_VALUE));
        try {
            table.applyRawBatch(List.of(RawMutation.of(
                    bytes("k"), bytes("v"), 1, 5_000)));
            KeyValueEntry entry = table.getEntry(bytes("k"));
            assertThat(entry.expireTimestamp()).isEqualTo(6_000);
        } finally {
            table.close();
        }
    }

    @Test
    void ttlZeroActsAsDelete() {
        AtomicLong clock = new AtomicLong(1_000);
        MemTable table = MemTable.createForTest(() -> clock.get(),
                new MemoryManager(Long.MAX_VALUE));
        try {
            table.applyRawBatch(List.of(RawMutation.of(
                    bytes("k"), bytes("v"), 1, 0)));
            assertThat(table.get(bytes("k"))).isNull();
            // 新键 ttl=0：删除为空操作，无 tombstone
            assertThat(table.getEntry(bytes("k"))).isNull();
        } finally {
            table.close();
        }
    }

    @Test
    void expiredTtlNotReadable() {
        AtomicLong clock = new AtomicLong(1_000);
        MemTable table = MemTable.createForTest(() -> clock.get(),
                new MemoryManager(Long.MAX_VALUE));
        try {
            table.applyRawBatch(List.of(RawMutation.of(
                    bytes("k"), bytes("v"), 1, 1_000)));
            assertThat(table.get(bytes("k"))).isNotNull();
            clock.set(2_001);
            assertThat(table.get(bytes("k"))).isNull();
        } finally {
            table.close();
        }
    }

    @Test
    void ownershipTransfersArraysWithoutCopy() {
        MemTable table = MemTable.create();
        try {
            byte[] key = bytes("owned-key");
            byte[] value = bytes("owned-value");
            RawMutation mutation = RawMutation.of(key, value, 7, -1);
            table.applyRawBatch(List.of(mutation));
            KeyValueEntry stored = table.getEntry(key);
            // 零拷贝契约：存储条目与输入共享数组实例
            assertThat(stored.key()).isSameAs(key);
            assertThat(stored.value()).isSameAs(value);
            assertThat(mutation.key()).isSameAs(key);
        } finally {
            table.close();
        }
    }

    @Test
    void segmentSpreadBatchStoresAll() {
        MemTable table = MemTable.create();
        try {
            List<RawMutation> mutations = new ArrayList<>();
            for (int i = 0; i < 512; i++) {
                mutations.add(RawMutation.of(bytes("spread-" + i), bytes("v"), i, -1));
            }
            table.applyRawBatch(mutations);
            assertThat(table.size()).isEqualTo(512);
            assertThat(table.get(bytes("spread-511"))).isEqualTo(bytes("v"));
        } finally {
            table.close();
        }
    }

    @Test
    void memoryAccountingTracksRawBatch() {
        MemoryManager memoryManager = new MemoryManager(Long.MAX_VALUE);
        MemTable table = MemTable.createForTest(() -> 1_000L, memoryManager);
        try {
            table.applyRawBatch(rawBatch(0, 10));
            assertThat(memoryManager.usedBytes()).isGreaterThan(0);
        } finally {
            table.close();
        }
    }

    @Test
    void liveSizeTracksRawBatch() {
        MemTable table = MemTable.create();
        try {
            table.applyRawBatch(rawBatch(0, 10));
            assertThat(table.size()).isEqualTo(10);
        } finally {
            table.close();
        }
    }

    @Test
    void concurrentRawBatchesSameKeysNoCorruption() throws Exception {
        MemTable table = MemTable.create();
        try {
            Thread a = new Thread(() -> {
                for (int round = 0; round < 50; round++) {
                    table.applyRawBatch(rawBatch(0, 20));
                }
            });
            Thread b = new Thread(() -> {
                for (int round = 0; round < 50; round++) {
                    table.applyRawBatch(rawBatch(0, 20));
                }
            });
            a.start();
            b.start();
            a.join(10_000);
            b.join(10_000);
            assertThat(table.size()).isEqualTo(20);
            for (int i = 0; i < 20; i++) {
                assertThat(table.get(bytes("k" + i))).isNotNull();
            }
        } finally {
            table.close();
        }
    }

    @Test
    void rawBatchAfterDeleteLatestWins() {
        MemTable table = MemTable.create();
        try {
            table.put(bytes("k"), bytes("old"));
            table.delete(bytes("k"));
            table.applyRawBatch(List.of(RawMutation.of(
                    bytes("k"), bytes("new"), 5, -1)));
            assertThat(table.get(bytes("k"))).isEqualTo(bytes("new"));
        } finally {
            table.close();
        }
    }

    @Test
    void iteratorSeesRawBatchInOrder() {
        MemTable table = MemTable.create();
        try {
            table.applyRawBatch(rawBatch(0, 30));
            int count = 0;
            byte[] previous = null;
            try (StorageIterator iterator = table.iterator()) {
                while (iterator.hasNext()) {
                    KeyValueEntry entry = iterator.next();
                    if (previous != null) {
                        assertThat(Arrays.compareUnsigned(entry.key(), previous))
                                .isPositive();
                    }
                    previous = entry.key();
                    count++;
                }
            }
            assertThat(count).isEqualTo(30);
        } finally {
            table.close();
        }
    }

    @Test
    void largeRawBatch2048() {
        MemTable table = MemTable.create();
        try {
            table.applyRawBatch(rawBatch(0, 2_048));
            assertThat(table.size()).isEqualTo(2_048);
        } finally {
            table.close();
        }
    }

    @Test
    void evictionCallbackFiresWhenOverLimit() {
        MemoryManager memoryManager = new MemoryManager(1_024);
        AtomicInteger calls = new AtomicInteger();
        memoryManager.setEvictionCallback(
                (used, max) -> calls.incrementAndGet());
        MemTable table = MemTable.createForTest(() -> 1_000L, memoryManager);
        try {
            table.applyRawBatch(rawBatch(0, 100));
            assertThat(calls.get()).isGreaterThan(0);
        } finally {
            table.close();
        }
    }

    @Test
    void rawBatchThenUpdateOverwritesVersion() {
        MemTable table = MemTable.create();
        try {
            table.applyRawBatch(List.of(RawMutation.of(
                    bytes("k"), bytes("v1"), 1, -1)));
            long firstVersion = table.getEntry(bytes("k")).version();
            table.applyRawBatch(List.of(RawMutation.of(
                    bytes("k"), bytes("v2"), 2, -1)));
            KeyValueEntry updated = table.getEntry(bytes("k"));
            assertThat(updated.version()).isGreaterThan(firstVersion);
            assertThat(updated.value()).isEqualTo(bytes("v2"));
        } finally {
            table.close();
        }
    }

    @Test
    void rawBatchApplyCountReturned() {
        MemTable table = MemTable.create();
        try {
            int applied = table.applyRawBatch(rawBatch(0, 42));
            assertThat(applied).isEqualTo(42);
        } finally {
            table.close();
        }
    }

    private static List<RawMutation> rawBatch(int from, int count) {
        List<RawMutation> mutations = new ArrayList<>(count);
        for (int i = from; i < from + count; i++) {
            mutations.add(RawMutation.of(
                    bytes("k" + i), bytes("v"), i, -1));
        }
        return mutations;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
