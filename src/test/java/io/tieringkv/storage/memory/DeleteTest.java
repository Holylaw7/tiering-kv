package io.tieringkv.storage.memory;

import io.tieringkv.storage.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DELETE 语义：tombstone 标记、读路径不可见、不立即物理删除（ADR-0007）。 */
class DeleteTest {

    private final MutableClock clock = new MutableClock(0);
    private MemTable table;

    @BeforeEach
    void setUp() {
        table = MemTable.createForTest(clock, new MemoryManager(1 << 30));
    }

    @Test
    void deleteReturnsTrueForExistingAndFalseForMissing() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        assertThat(table.delete(key)).isFalse();
        table.put(key, "v".getBytes(StandardCharsets.UTF_8));
        assertThat(table.delete(key)).isTrue();
        assertThat(table.delete(key)).isFalse();
    }

    @Test
    void deletedKeyIsInvisibleToReadPaths() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        table.put(key, "v".getBytes(StandardCharsets.UTF_8));
        table.delete(key);
        assertThat(table.get(key)).isNull();
        assertThat(table.exists(key)).isFalse();
        assertThat(table.size()).isZero();
    }

    @Test
    void iteratorSkipsTombstones() {
        table.put("a".getBytes(StandardCharsets.UTF_8), "1".getBytes(StandardCharsets.UTF_8));
        table.put("b".getBytes(StandardCharsets.UTF_8), "2".getBytes(StandardCharsets.UTF_8));
        table.delete("b".getBytes(StandardCharsets.UTF_8));
        List<String> keys = new ArrayList<>();
        try (var iterator = table.iterator()) {
            while (iterator.hasNext()) {
                keys.add(new String(iterator.next().key(), StandardCharsets.UTF_8));
            }
        }
        assertThat(keys).containsExactly("a");
    }

    @Test
    void rePutAfterDeleteWorks() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        table.put(key, "old".getBytes(StandardCharsets.UTF_8));
        table.delete(key);
        table.put(key, "new".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(table.get(key), StandardCharsets.UTF_8)).isEqualTo("new");
        assertThat(table.size()).isEqualTo(1);
        assertThat(table.delete(key)).isTrue();
    }
}
