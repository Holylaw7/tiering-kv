package io.tieringkv.storage.cache;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationCallbackTest {

    @Test
    void callbackReceivesEntryBeforeDeletion() {
        MutableClock clock = new MutableClock(0);
        MemoryManager memoryManager = new MemoryManager(200);
        MemTable memTable = MemTable.createForTest(clock, memoryManager);
        LFUPolicy policy = new LFUPolicy(new HotnessTracker(1000));
        List<KeyValueEntry> migrated = new ArrayList<>();

        EvictionManager evictionManager = new EvictionManager(
                memTable, memoryManager, policy, entry -> {
                    // 回调执行时条目必须仍在内存（先迁移、后删除）
                    assertThat(memTable.getEntry(entry.key())).isNotNull();
                    migrated.add(entry);
                }, clock, 64);
        TrackingStorageEngine storage = new TrackingStorageEngine(memTable, evictionManager, clock);

        for (int i = 0; i < 6; i++) {
            storage.put(("k" + i).getBytes(StandardCharsets.UTF_8), new byte[32]);
        }

        assertThat(migrated).isNotEmpty();
        for (KeyValueEntry entry : migrated) {
            assertThat(entry.value()).isNotNull();
            assertThat(entry.size()).isGreaterThan(0);
            assertThat(memTable.getEntry(entry.key())).isNull(); // 迁移后已删除
        }
    }
}
