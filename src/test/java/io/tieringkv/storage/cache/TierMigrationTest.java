package io.tieringkv.storage.cache;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 迁移结果码语义（ADR-0013）：SUCCESS 迁移后删除；FAILED/RETRY 保留数据。 */
class TierMigrationTest {

    @Test
    void successMigratesBeforeDeletion() {
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
                    return MigrationResult.SUCCESS;
                }, clock, 64);
        TrackingStorageEngine storage = new TrackingStorageEngine(memTable, evictionManager, clock);

        for (int i = 0; i < 6; i++) {
            storage.put(("k" + i).getBytes(StandardCharsets.UTF_8), new byte[32]);
        }

        assertThat(migrated).isNotEmpty();
        for (KeyValueEntry entry : migrated) {
            assertThat(entry.value()).isNotNull();
            assertThat(entry.size()).isGreaterThan(0);
            assertThat(memTable.getEntry(entry.key())).isNull(); // 迁移成功后已删除
        }
    }

    @Test
    void failedResultKeepsEntryAndStopsCycle() {
        Stack stack = new Stack(() -> MigrationResult.FAILED);
        stack.storage.put("k0".getBytes(StandardCharsets.UTF_8), new byte[32]);
        stack.storage.put("k1".getBytes(StandardCharsets.UTF_8), new byte[32]);
        assertThat(stack.attempts).hasValue(1);
        assertThat(stack.memTable.size()).isEqualTo(2); // 全部保留
        assertThat(stack.memTable.getEntry("k0".getBytes(StandardCharsets.UTF_8))).isNotNull();
    }

    @Test
    void retryIsBoundedThenKeepsEntry() {
        Stack stack = new Stack(() -> MigrationResult.RETRY);
        stack.storage.put("k0".getBytes(StandardCharsets.UTF_8), new byte[32]);
        stack.storage.put("k1".getBytes(StandardCharsets.UTF_8), new byte[32]);
        assertThat(stack.attempts).hasValue(3); // 每轮重试预算
        assertThat(stack.memTable.size()).isEqualTo(2);
    }

    @Test
    void retryThenSuccessRemovesEntry() {
        AtomicInteger calls = new AtomicInteger();
        Stack stack = new Stack(() -> calls.incrementAndGet() == 1
                ? MigrationResult.RETRY
                : MigrationResult.SUCCESS);
        stack.storage.put("k0".getBytes(StandardCharsets.UTF_8), new byte[32]);
        stack.storage.put("k1".getBytes(StandardCharsets.UTF_8), new byte[32]);

        assertThat(calls).hasValue(2);
        assertThat(stack.memTable.size()).isEqualTo(1);
        assertThat(stack.memTable.getEntry("k0".getBytes(StandardCharsets.UTF_8))).isNull();
    }

    private static final class Stack {
        private final MutableClock clock = new MutableClock(0);
        private final MemoryManager memoryManager = new MemoryManager(150);
        private final MemTable memTable = MemTable.createForTest(clock, memoryManager);
        private final LFUPolicy policy = new LFUPolicy(new HotnessTracker(1000));
        private final AtomicInteger attempts = new AtomicInteger();
        private final TrackingStorageEngine storage;

        private Stack(ResultSupplier supplier) {
            EvictionManager evictionManager = new EvictionManager(
                    memTable, memoryManager, policy,
                    entry -> {
                        attempts.incrementAndGet();
                        return supplier.get();
                    }, clock, 64);
            storage = new TrackingStorageEngine(memTable, evictionManager, clock);
        }

        @FunctionalInterface
        private interface ResultSupplier {
            MigrationResult get();
        }
    }
}
