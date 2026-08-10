package io.tieringkv.cluster.migration.streaming;

import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.cluster.sharding.SlotTable;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.Mutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 流式迁移（ADR-0053）：流式复制/游标/恢复/版本屏障/动态 batch。 */
class StreamingMigrationTest {

    @TempDir
    Path dir;

    @Test
    void streamingCopyCopiesAll() throws Exception {
        MemTable source = source(100, 100);
        MemTable target = MemTable.create();
        try {
            StreamingMigrator migrator = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            while (!migrator.runBatch(512)) {
            }
            assertThat(target.size()).isEqualTo(100);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void filtersBySlotRange() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            int inRange = 0;
            for (int i = 0; i < 300; i++) {
                byte[] key = key(i);
                source.put(key, value());
                if (HashSlotRouter.slot(key) <= 5000) {
                    inRange++;
                }
            }
            StreamingMigrator migrator = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, 5000, 1, Long.MAX_VALUE);
            while (!migrator.runBatch(512)) {
            }
            assertThat(target.size()).isEqualTo(inRange);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void pauseResumeFromCursor() throws Exception {
        MemTable source = source(100, 100);
        MemTable target = MemTable.create();
        try {
            StreamingMigrator first = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            first.runBatch(10);
            assertThat(Files.exists(dir.resolve("slot-0.cursor"))).isTrue();
            StreamingMigrator resumed = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            MigrationStreamCursor cursor = resumed.load();
            assertThat(cursor.offset()).isEqualTo(10);
            while (!resumed.runBatch(512)) {
            }
            assertThat(target.size()).isEqualTo(100);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void crashRecoveryContinues() throws Exception {
        MemTable source = source(100, 100);
        MemTable target = MemTable.create();
        try {
            StreamingMigrator first = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            first.runBatch(10);
            // 模拟崩溃：直接丢弃 migrator，新实例从游标继续
            StreamingMigrator recovered = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            while (!recovered.runBatch(512)) {
            }
            assertThat(target.size()).isEqualTo(100);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void corruptCursorFallsBackToStart() throws Exception {
        MemTable source = source(50, 100);
        MemTable target = MemTable.create();
        try {
            StreamingMigrator first = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            first.runBatch(10);
            byte[] bytes = Files.readAllBytes(dir.resolve("slot-0.cursor"));
            bytes[bytes.length - 1] ^= 0x01;
            Files.write(dir.resolve("slot-0.cursor"), bytes);
            StreamingMigrator recovered = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            assertThat(recovered.load().offset()).isZero();
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void versionBarrierSkipsNewerEntries() throws Exception {
        List<io.tieringkv.storage.memory.KeyValueEntry> entries = new ArrayList<>();
        entries.add(entry(key(0), 1));
        entries.add(entry(key(1), 2));
        try (io.tieringkv.storage.StorageIterator iterator = new ListIterator(entries);
             ) {
            MigrationScanner scanner = new MigrationScanner(iterator, 0,
                    HashSlotRouter.SLOT_COUNT - 1, 1, new byte[0]);
            assertThat(scanner.hasNext()).isTrue();
            assertThat(scanner.next().version()).isEqualTo(1);
            assertThat(scanner.hasNext()).isFalse();
        }
    }

    private static io.tieringkv.storage.memory.KeyValueEntry entry(byte[] key, long version) {
        return io.tieringkv.storage.memory.KeyValueEntry.live(
                key, value(), 0, -1, version);
    }

    private static final class ListIterator implements io.tieringkv.storage.StorageIterator {
        private final List<io.tieringkv.storage.memory.KeyValueEntry> entries;
        private int index;

        private ListIterator(List<io.tieringkv.storage.memory.KeyValueEntry> entries) {
            this.entries = entries;
        }

        @Override
        public boolean hasNext() {
            return index < entries.size();
        }

        @Override
        public io.tieringkv.storage.memory.KeyValueEntry next() {
            return entries.get(index++);
        }

        @Override
        public void close() {
        }
    }

    @Test
    void concurrentUpdateNotOverwritten() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            source.put(key(0), value());
            StreamingMigrator migrator = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            migrator.runBatch(10); // key0 已复制
            source.put(key(0), value()); // 迁移期间更新
            assertThat(target.getEntry(key(0)).version())
                    .isNotEqualTo(source.getEntry(key(0)).version());
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void dynamicBatchSmallEntries() {
        assertThat(BatchEncoder.batchSizeFor(100)).isEqualTo(4096);
    }

    @Test
    void dynamicBatchMediumEntries() {
        assertThat(BatchEncoder.batchSizeFor(1024)).isEqualTo(1024);
    }

    @Test
    void dynamicBatchLargeEntries() {
        assertThat(BatchEncoder.batchSizeFor(10_000)).isEqualTo(256);
    }

    @Test
    void encoderDrainsInOrder() {
        BatchEncoder encoder = new BatchEncoder(3);
        encoder.add(Mutation.put(key(0), value()));
        encoder.add(Mutation.put(key(1), value()));
        assertThat(encoder.isFull()).isFalse();
        encoder.add(Mutation.put(key(2), value()));
        assertThat(encoder.isFull()).isTrue();
        List<Mutation> batch = encoder.drain();
        assertThat(batch).hasSize(3);
        assertThat(encoder.isEmpty()).isTrue();
    }

    @Test
    void scannerSkipsAlreadyCopiedKeys() throws Exception {
        MemTable source = source(10, 100);
        byte[] first = key(0);
        try (io.tieringkv.storage.StorageIterator iterator = source.iterator();
             ) {
            MigrationScanner scanner = new MigrationScanner(iterator, 0,
                    HashSlotRouter.SLOT_COUNT - 1, Long.MAX_VALUE, first);
            assertThat(scanner.hasNext()).isTrue();
            byte[] nextKey = scanner.next().key();
            assertThat(compare(nextKey, first)).isGreaterThan(0);
        } finally {
            source.close();
        }
    }

    @Test
    void checksumAdvancesWithOffset() throws Exception {
        MemTable source = source(20, 100);
        MemTable target = MemTable.create();
        try {
            StreamingMigrator migrator = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            migrator.runBatch(5);
            MigrationStreamCursor cursor = migrator.load();
            assertThat(cursor.offset()).isEqualTo(5);
            assertThat(cursor.checksum()).isNotZero();
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void emptySourceCompletes() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            StreamingMigrator migrator = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            assertThat(migrator.runBatch(512)).isTrue();
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void switchUpdatesSlotTable() throws Exception {
        MemTable source = source(10, 100);
        MemTable target = MemTable.create();
        try {
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            StreamingMigrator migrator = new StreamingMigrator(source, target,
                    slotTable, dir, 0, 5000, 1, Long.MAX_VALUE);
            while (!migrator.runBatch(512)) {
            }
            assertThat(slotTable.shardFor(100)).isEqualTo(1);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void cursorFileRemovedOnDone() throws Exception {
        MemTable source = source(10, 100);
        MemTable target = MemTable.create();
        try {
            StreamingMigrator migrator = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            while (!migrator.runBatch(512)) {
            }
            assertThat(Files.exists(dir.resolve("slot-0.cursor"))).isFalse();
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void cursorRoundTripFields() throws Exception {
        MemTable source = source(10, 100);
        MemTable target = MemTable.create();
        try {
            StreamingMigrator migrator = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            migrator.runBatch(3);
            MigrationStreamCursor loaded = migrator.load();
            assertThat(loaded.slotId()).isZero();
            assertThat(loaded.lastVersion()).isGreaterThanOrEqualTo(0);
            assertThat(loaded.lastKey()).isNotEmpty();
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void multiBatchStreaming() throws Exception {
        MemTable source = source(500, 100);
        MemTable target = MemTable.create();
        try {
            StreamingMigrator migrator = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            int batches = 0;
            while (!migrator.runBatch(64)) {
                batches++;
            }
            assertThat(batches).isGreaterThan(1);
            assertThat(target.size()).isEqualTo(500);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void streamingMigrationUsesZeroCopyOwnership() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            byte[] key = key(0);
            byte[] value = value();
            source.put(key, value);
            StreamingMigrator migrator = new StreamingMigrator(source, target,
                    new SlotTable(), dir, 0, HashSlotRouter.SLOT_COUNT - 1, 1,
                    Long.MAX_VALUE);
            while (!migrator.runBatch(512)) {
            }
            io.tieringkv.storage.memory.KeyValueEntry stored =
                    target.getEntry(key);
            // 零拷贝路径（ADR-0059）：目标条目与源快照共享数组实例
            assertThat(stored.value()).isSameAs(source.getEntry(key).value());
            assertThat(stored.key()).isSameAs(source.getEntry(key).key());
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void invalidBatchSizeRejected() {
        assertThatThrownBy(() -> new BatchEncoder(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MemTable source(int count, int valueSize) {
        MemTable source = MemTable.create();
        byte[] value = new byte[valueSize];
        java.util.Arrays.fill(value, (byte) 'v');
        for (int i = 0; i < count; i++) {
            source.put(key(i), value);
        }
        return source;
    }

    private static byte[] key(int i) {
        return ("stream:" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value() {
        return new byte[16];
    }

    private static int compare(byte[] a, byte[] b) {
        return java.util.Arrays.compareUnsigned(a, b);
    }
}
