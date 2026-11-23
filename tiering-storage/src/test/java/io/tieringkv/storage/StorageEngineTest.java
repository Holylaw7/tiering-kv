package io.tieringkv.storage;

import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 通过 StorageEngine 接口验证完整生命周期（Command 层视角）。 */
class StorageEngineTest {

    private final StorageEngine storage = MemTable.createForTest(
            new MutableClock(0), new MemoryManager(1 << 30));

    @Test
    void fullLifecycleThroughInterface() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        byte[] value = "v".getBytes(StandardCharsets.UTF_8);

        assertThat(storage.size()).isZero();
        storage.put(key, value);
        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.get(key)).isEqualTo(value);
        assertThat(storage.size()).isEqualTo(1);

        try (StorageIterator iterator = storage.iterator()) {
            assertThat(iterator.hasNext()).isTrue();
            KeyValueEntry entry = iterator.next();
            assertThat(entry.key()).isEqualTo(key);
            assertThat(entry.value()).isEqualTo(value);
            assertThat(iterator.hasNext()).isFalse();
        }

        assertThat(storage.delete(key)).isTrue();
        assertThat(storage.exists(key)).isFalse();
        assertThat(storage.get(key)).isNull();
        assertThat(storage.size()).isZero();
    }
}
