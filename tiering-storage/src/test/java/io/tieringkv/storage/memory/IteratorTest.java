package io.tieringkv.storage.memory;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.StorageIterator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 全局有序迭代：跨 64 段归并、跳过 tombstone 与过期键。 */
class IteratorTest {

    private final MutableClock clock = new MutableClock(0);
    private MemTable table;

    @BeforeEach
    void setUp() {
        table = MemTable.createForTest(clock, new MemoryManager(1 << 30));
    }

    @Test
    void returnsEntriesInKeyOrderAcrossSegments() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            keys.add(String.format("key-%03d", i));
        }
        Collections.shuffle(keys);
        for (String key : keys) {
            table.put(key.getBytes(StandardCharsets.UTF_8), "v".getBytes(StandardCharsets.UTF_8));
        }

        List<String> actual = collectKeys();
        assertThat(actual).hasSize(300);
        assertThat(actual).isSortedAccordingTo((a, b) -> Arrays.compareUnsigned(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void skipsTombstonesAndExpiredEntries() {
        table.put("a".getBytes(StandardCharsets.UTF_8), "1".getBytes(StandardCharsets.UTF_8));
        table.put("b".getBytes(StandardCharsets.UTF_8), "2".getBytes(StandardCharsets.UTF_8), 100);
        table.put("c".getBytes(StandardCharsets.UTF_8), "3".getBytes(StandardCharsets.UTF_8));
        table.delete("c".getBytes(StandardCharsets.UTF_8));
        clock.advance(101);
        assertThat(collectKeys()).containsExactly("a");
    }

    @Test
    void emptyTableHasNoElements() {
        try (StorageIterator iterator = table.iterator()) {
            assertThat(iterator.hasNext()).isFalse();
        }
    }

    @Test
    void closeIsIdempotent() {
        table.put("a".getBytes(StandardCharsets.UTF_8), "1".getBytes(StandardCharsets.UTF_8));
        StorageIterator iterator = table.iterator();
        iterator.close();
        iterator.close();
        assertThat(iterator.hasNext()).isTrue();
    }

    private List<String> collectKeys() {
        List<String> keys = new ArrayList<>();
        try (StorageIterator iterator = table.iterator()) {
            while (iterator.hasNext()) {
                keys.add(new String(iterator.next().key(), StandardCharsets.UTF_8));
            }
        }
        return keys;
    }
}
