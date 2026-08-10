package io.tieringkv.cluster.migration.streaming;

import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.cluster.sharding.SlotTable;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.RawMutation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * 流式迁移（ADR-0053）：scan batch → encode → apply → checksum →
 * cursor checkpoint；支持 pause/resume/recover 与版本屏障。
 */
public final class StreamingMigrator implements AutoCloseable {

    private static final int CURSOR_MAGIC = 0x4D535452; // 'MSTR'
    private static final byte CURSOR_VERSION = 1;

    private final StorageEngine source;
    private final StorageEngine target;
    private final SlotTable slotTable;
    private final Path cursorDir;
    private final int slotStart;
    private final int slotEnd;
    private final int targetShardId;
    private final long versionBarrier;
    private StorageIterator openIterator;
    private MigrationScanner scanner;

    public StreamingMigrator(StorageEngine source, StorageEngine target,
                             SlotTable slotTable, Path cursorDir,
                             int slotStart, int slotEnd, int targetShardId,
                             long versionBarrier) {
        this.source = source;
        this.target = target;
        this.slotTable = slotTable;
        this.cursorDir = cursorDir;
        this.slotStart = slotStart;
        this.slotEnd = slotEnd;
        this.targetShardId = targetShardId;
        this.versionBarrier = versionBarrier;
    }

    /** 执行一个批次；返回是否完成。 */
    public boolean runBatch(int batchSize) throws IOException {
        MigrationStreamCursor cursor = load();
        if (scanner == null) {
            // 持久 scanner：整个迁移只做一次全量快照，跨批次复用，
            // 避免每批重建 O(N) 迭代器；同时保证迁移开始前的数据不会被
            // 迁移期间的新快照跳过（版本屏障一致性）。
            openIterator = source.iterator();
            scanner = new MigrationScanner(
                    openIterator, slotStart, slotEnd, versionBarrier, cursor.lastKey());
        }
        List<RawMutation> batch = new java.util.ArrayList<>(batchSize);
        CRC32C crc = new CRC32C();
        crc.update(longToBytes(cursor.checksum()));
        int copied = 0;
        while (scanner.hasNext() && copied < batchSize) {
            KeyValueEntry entry = scanner.next();
            long ttl = entry.expireTimestamp() >= 0
                    ? Math.max(0, entry.expireTimestamp() - System.currentTimeMillis())
                    : StorageEngine.NO_TTL;
            // 零拷贝路径（ADR-0059）：所有权随 applyRawBatch 转移，不克隆
            batch.add(new RawMutation(entry.key(), entry.value(),
                    entry.version(), ttl));
            crc.update(entry.key());
            crc.update(entry.value() == null ? new byte[0] : entry.value());
            cursor = cursor.advance(entry.key(), entry.version(), crc.getValue());
            copied++;
        }
        if (!batch.isEmpty()) {
            target.applyRawBatch(batch);
        }
        persist(cursor);
        boolean completed = !scanner.hasNext();
        if (completed) {
            switchTraffic(cursor);
            close();
        }
        return completed;
    }

    @Override
    public void close() {
        if (openIterator != null) {
            openIterator.close();
            openIterator = null;
        }
        scanner = null;
    }

    public MigrationStreamCursor load() throws IOException {
        Path file = cursorFile();
        if (!Files.exists(file)) {
            return MigrationStreamCursor.empty(slotStart);
        }
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != CURSOR_MAGIC) {
            return MigrationStreamCursor.empty(slotStart);
        }
        byte version = buffer.get();
        if (version != CURSOR_VERSION) {
            return MigrationStreamCursor.empty(slotStart);
        }
        int payloadStart = buffer.position();
        int slotId = buffer.getInt();
        int keyLength = buffer.getInt();
        byte[] lastKey = new byte[keyLength];
        buffer.get(lastKey);
        long lastVersion = buffer.getLong();
        long offset = buffer.getLong();
        long checksum = buffer.getLong();
        int payloadEnd = buffer.position();
        int expectedCrc = buffer.getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, payloadStart, payloadEnd - payloadStart);
        if (crc.getValue() != (expectedCrc & 0xffffffffL)) {
            return MigrationStreamCursor.empty(slotStart);
        }
        return new MigrationStreamCursor(slotId, lastKey, lastVersion, offset, checksum);
    }

    private void persist(MigrationStreamCursor cursor) throws IOException {
        Files.createDirectories(cursorDir);
        ByteBuffer payload = ByteBuffer.allocate(
                4 + 4 + cursor.lastKey().length + 8 + 8 + 8)
                .order(ByteOrder.BIG_ENDIAN);
        payload.putInt(cursor.slotId());
        payload.putInt(cursor.lastKey().length);
        payload.put(cursor.lastKey());
        payload.putLong(cursor.lastVersion());
        payload.putLong(cursor.offset());
        payload.putLong(cursor.checksum());
        byte[] payloadBytes = payload.array();
        CRC32C crc = new CRC32C();
        crc.update(payloadBytes);
        ByteBuffer out = ByteBuffer.allocate(4 + 1 + payloadBytes.length + 4)
                .order(ByteOrder.BIG_ENDIAN);
        out.putInt(CURSOR_MAGIC);
        out.put(CURSOR_VERSION);
        out.put(payloadBytes);
        out.putInt((int) crc.getValue());
        Files.write(cursorFile(), out.array());
    }

    private void switchTraffic(MigrationStreamCursor cursor) throws IOException {
        for (int slot = slotStart; slot <= slotEnd; slot++) {
            slotTable.reassign(slot, targetShardId);
        }
        Files.deleteIfExists(cursorFile());
    }

    private Path cursorFile() {
        return cursorDir.resolve("slot-" + slotStart + ".cursor");
    }

    private static byte[] longToBytes(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array();
    }
}
