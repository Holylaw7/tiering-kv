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

class EvictionManagerTest {

    @Test
    void evictionTriggersWhenMemoryOverLimit() {
        Stack stack = new Stack(300);
        for (int i = 0; i < 8; i++) {
            stack.storage.put(("k" + i).getBytes(StandardCharsets.UTF_8), value(32));
        }
        assertThat(stack.migration.count()).isGreaterThanOrEqualTo(1);
        assertThat(stack.memTable.size()).isLessThan(8);
        for (KeyValueEntry entry : stack.migration.migrated) {
            assertThat(entry.value()).isNotNull();
        }
    }

    @Test
    void hotKeySurvivesEviction() {
        Stack stack = new Stack(150);
        byte[] hotKey = "k0".getBytes(StandardCharsets.UTF_8);
        stack.storage.put(hotKey, value(32));
        for (int i = 0; i < 10; i++) {
            stack.storage.get(hotKey);
        }
        for (int i = 1; i < 8; i++) {
            stack.storage.put(("k" + i).getBytes(StandardCharsets.UTF_8), value(32));
        }
        assertThat(stack.storage.get(hotKey)).isNotNull();
        assertThat(stack.memTable.size()).isEqualTo(1);
    }

    @Test
    void expiredCandidateIsSkippedAndCleaned() {
        Stack stack = new Stack(200);
        byte[] expiredKey = "e".getBytes(StandardCharsets.UTF_8);
        stack.storage.put(expiredKey, value(32), 50);
        byte[] liveKey = "k".getBytes(StandardCharsets.UTF_8);
        stack.storage.put(liveKey, value(32));
        stack.clock.advance(100);
        stack.storage.put("f".getBytes(StandardCharsets.UTF_8), value(32));
        stack.evictionManager.maybeEvict();
        assertThat(stack.migration.count()).isEqualTo(1);
        assertThat(stack.migration.migrated.get(0).key())
                .isEqualTo(liveKey);
        // 过期键已从策略清理，剩余候选为未迁移的 f
        assertThat(stack.policy.selectCandidate().key())
                .isEqualTo("f".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void evictionLoopIsBounded() {
        Stack stack = new Stack(100, 3);
        // 预置 4 个小键（数据 + 策略索引同步），再写入大值，单轮需淘汰 > 预算
        for (int i = 0; i < 4; i++) {
            byte[] key = ("s" + i).getBytes(StandardCharsets.UTF_8);
            stack.memTable.put(key, value(1));
            stack.policy.onAccess(new AccessEvent(key, AccessEvent.AccessOperation.PUT, 0, 67));
        }
        stack.storage.put("big".getBytes(StandardCharsets.UTF_8), value(1000));
        assertThat(stack.migration.count()).isEqualTo(3);
        assertThat(stack.memTable.size()).isEqualTo(2);
    }

    @Test
    void noEvictionUnderLimit() {
        Stack stack = new Stack(1000);
        stack.storage.put("k".getBytes(StandardCharsets.UTF_8), value(16));
        assertThat(stack.migration.count()).isZero();
    }

    private static byte[] value(int size) {
        byte[] value = new byte[size];
        for (int i = 0; i < size; i++) {
            value[i] = (byte) i;
        }
        return value;
    }

    private static final class Stack {
        private final MutableClock clock = new MutableClock(0);
        private final MemoryManager memoryManager;
        private final MemTable memTable;
        private final LFUPolicy policy;
        private final CountingMigration migration;
        private final EvictionManager evictionManager;
        private final TrackingStorageEngine storage;

        private Stack(long maxBytes) {
            this(maxBytes, 64);
        }

        private Stack(long maxBytes, int maxEvictionsPerCycle) {
            memoryManager = new MemoryManager(maxBytes);
            memTable = MemTable.createForTest(clock, memoryManager);
            policy = new LFUPolicy(new HotnessTracker(1000));
            migration = new CountingMigration();
            evictionManager = new EvictionManager(
                    memTable, memoryManager, policy, migration, clock, maxEvictionsPerCycle);
            storage = new TrackingStorageEngine(memTable, evictionManager, clock);
        }
    }

    private static final class CountingMigration implements MigrationCallback {
        private final List<KeyValueEntry> migrated = new ArrayList<>();

        @Override
        public void migrate(KeyValueEntry entry) {
            migrated.add(entry);
        }

        private int count() {
            return migrated.size();
        }
    }
}
