package io.tieringkv.cluster.migration;

import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.cluster.sharding.SlotTable;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32C;

/**
 * 游标 Slot 迁移（ADR-0045）：
 * INIT → COPYING ⇄ PAUSED → VERIFYING → SWITCHING → DONE；
 * 单个迭代器跨批次推进（MigrationCursor），checkpoint 持久化到
 * migration/slot-{start}.cursor（CRC 保护），支持暂停/恢复/崩溃续传。
 */
public final class SlotMigrationManager {

    private static final int CURSOR_MAGIC = 0x4D435352; // 'MCSR'
    private static final byte CURSOR_VERSION = 1;

    private final SlotTable slotTable;
    private final Path checkpointDir;
    private final Map<String, StorageIterator> openIterators = new ConcurrentHashMap<>();

    public SlotMigrationManager(SlotTable slotTable, Path checkpointDir) {
        this.slotTable = slotTable;
        this.checkpointDir = checkpointDir;
    }

    public MigrationState start(MigrationTask task) throws IOException {
        synchronized (task.lock()) {
            task.state(MigrationState.COPYING);
            task.cursor(MigrationCursor.empty());
            task.checkpoint(MigrationCheckpoint.empty().withState(MigrationState.COPYING));
            persist(task);
            return task.state();
        }
    }

    /** 执行一个批次；返回当前状态。 */
    public MigrationState runBatch(MigrationTask task, int batchSize) throws IOException {
        synchronized (task.lock()) {
            while (true) {
                switch (task.state()) {
                    case INIT -> task.state(MigrationState.COPYING);
                    case COPYING -> {
                        copyBatch(task, batchSize);
                        return task.state();
                    }
                    case VERIFYING -> {
                        verify(task);
                        return task.state();
                    }
                    case SWITCHING -> {
                        switchTraffic(task);
                        return task.state();
                    }
                    default -> {
                        return task.state();
                    }
                }
            }
        }
    }

    /** 暂停：关闭迭代器并持久化游标。 */
    public MigrationState pause(MigrationTask task) throws IOException {
        synchronized (task.lock()) {
            if (task.state() != MigrationState.COPYING) {
                return task.state();
            }
            closeIterator(task.taskId());
            task.state(MigrationState.PAUSED);
            task.checkpoint(task.checkpoint().withState(MigrationState.PAUSED));
            persist(task);
            return task.state();
        }
    }

    /** 恢复：PAUSED → COPYING（迭代器在下一批次懒重建）。 */
    public MigrationState resume(MigrationTask task) throws IOException {
        synchronized (task.lock()) {
            if (task.state() != MigrationState.PAUSED) {
                return task.state();
            }
            task.state(MigrationState.COPYING);
            task.checkpoint(task.checkpoint().withState(MigrationState.COPYING));
            persist(task);
            return task.state();
        }
    }

    /** 从持久化游标恢复未完成任务（含崩溃恢复）。 */
    public MigrationTask recover(MigrationTask task) throws IOException {
        synchronized (task.lock()) {
            CursorFile saved = load(task.slotStart());
            if (saved != null) {
                task.cursor(new MigrationCursor(saved.lastKey(), saved.lastVersion(),
                        saved.checkpointOffset()));
                task.checkpoint(new MigrationCheckpoint(saved.lastKey(),
                        saved.copiedEntries(), saved.copiedBytes(), 0,
                        saved.checkpointOffset(), saved.state()));
                task.state(switch (saved.state()) {
                    case DONE -> MigrationState.DONE;
                    case VERIFYING -> MigrationState.VERIFYING;
                    default -> MigrationState.COPYING;
                });
            } else {
                // 无游标/游标损坏：从头开始
                task.state(MigrationState.COPYING);
                task.cursor(MigrationCursor.empty());
                task.checkpoint(MigrationCheckpoint.empty().withState(MigrationState.COPYING));
            }
            return task;
        }
    }

    public MigrationCheckpoint checkpoint(MigrationTask task) {
        return task.checkpoint();
    }

    private void copyBatch(MigrationTask task, int batchSize) throws IOException {
        StorageIterator iterator = openIterator(task);
        MigrationCursor cursor = task.cursor();
        MigrationCheckpoint checkpoint = task.checkpoint();
        long entries = checkpoint.copiedEntries();
        long bytes = checkpoint.copiedBytes();
        int copied = 0;
        while (iterator.hasNext() && copied < batchSize) {
            KeyValueEntry entry = iterator.next();
            if (!inRange(task, entry.key())
                    || compare(entry.key(), cursor.lastKey()) <= 0) {
                continue;
            }
            long ttl = entry.expireTimestamp() >= 0
                    ? Math.max(0, entry.expireTimestamp() - System.currentTimeMillis())
                    : StorageEngine.NO_TTL;
            task.target().put(entry.key(), entry.value(), ttl);
            cursor = cursor.advance(entry.key(), entry.version());
            entries++;
            bytes += entry.size();
            copied++;
        }
        boolean completed = !iterator.hasNext();
        if (completed) {
            closeIterator(task.taskId());
        }
        task.cursor(cursor);
        MigrationCheckpoint updated = new MigrationCheckpoint(
                cursor.lastKey(), entries, bytes, 0, cursor.checkpointOffset(),
                MigrationState.COPYING);
        task.checkpoint(updated);
        if (completed) {
            task.state(MigrationState.VERIFYING);
            task.checkpoint(updated.withState(MigrationState.VERIFYING));
        }
        persist(task);
    }

    private StorageIterator openIterator(MigrationTask task) {
        return openIterators.computeIfAbsent(task.taskId(), id -> task.source().iterator());
    }

    private void closeIterator(String taskId) {
        StorageIterator iterator = openIterators.remove(taskId);
        if (iterator != null) {
            iterator.close();
        }
    }

    private void verify(MigrationTask task) throws IOException {
        MigrationCheckpoint checkpoint = task.checkpoint();
        CrcCount target = scan(task.target(), task);
        CrcCount source = scan(task.source(), task);
        if (target.count() == checkpoint.copiedEntries()
                && target.count() == source.count()
                && target.checksum() == source.checksum()) {
            task.state(MigrationState.SWITCHING);
            task.checkpoint(checkpoint.withState(MigrationState.SWITCHING));
            persist(task);
        } else {
            task.state(MigrationState.FAILED);
            task.checkpoint(checkpoint.withState(MigrationState.FAILED));
            persist(task);
        }
    }

    private CrcCount scan(StorageEngine engine, MigrationTask task) {
        CRC32C crc = new CRC32C();
        long count = 0;
        try (StorageIterator iterator = engine.iterator()) {
            while (iterator.hasNext()) {
                KeyValueEntry entry = iterator.next();
                if (!inRange(task, entry.key())) {
                    continue;
                }
                crc.update(entry.key());
                crc.update(entry.value() == null ? new byte[0] : entry.value());
                count++;
            }
        }
        return new CrcCount(count, crc.getValue());
    }

    private void switchTraffic(MigrationTask task) throws IOException {
        for (int slot = task.slotStart(); slot <= task.slotEnd(); slot++) {
            slotTable.reassign(slot, task.targetShardId());
        }
        try (StorageIterator iterator = task.source().iterator()) {
            while (iterator.hasNext()) {
                KeyValueEntry entry = iterator.next();
                if (inRange(task, entry.key())) {
                    task.source().delete(entry.key());
                }
            }
        }
        task.state(MigrationState.DONE);
        task.checkpoint(task.checkpoint().withState(MigrationState.DONE));
        persist(task);
    }

    private boolean inRange(MigrationTask task, byte[] key) {
        int slot = HashSlotRouter.slot(key);
        return slot >= task.slotStart() && slot <= task.slotEnd();
    }

    private void persist(MigrationTask task) throws IOException {
        Files.createDirectories(checkpointDir);
        MigrationCheckpoint checkpoint = task.checkpoint();
        MigrationCursor cursor = task.cursor();
        ByteBuffer payload = ByteBuffer.allocate(
                4 + 4 + 4 + cursor.lastKey().length + 8 + 8 + 8 + 8 + 1)
                .order(ByteOrder.BIG_ENDIAN);
        payload.putInt(task.slotStart());
        payload.putInt(task.slotEnd());
        payload.putInt(cursor.lastKey().length);
        payload.put(cursor.lastKey());
        payload.putLong(cursor.lastVersion());
        payload.putLong(cursor.checkpointOffset());
        payload.putLong(checkpoint.copiedEntries());
        payload.putLong(checkpoint.copiedBytes());
        payload.put((byte) checkpoint.state().ordinal());
        byte[] payloadBytes = payload.array();
        CRC32C crc = new CRC32C();
        crc.update(payloadBytes);

        ByteBuffer out = ByteBuffer.allocate(4 + 1 + payloadBytes.length + 4)
                .order(ByteOrder.BIG_ENDIAN);
        out.putInt(CURSOR_MAGIC);
        out.put(CURSOR_VERSION);
        out.put(payloadBytes);
        out.putInt((int) crc.getValue());
        Files.write(checkpointDir.resolve(cursorFile(task.slotStart())), out.array());
    }

    private CursorFile load(int slotStart) throws IOException {
        Path file = checkpointDir.resolve(cursorFile(slotStart));
        if (!Files.exists(file)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != CURSOR_MAGIC) {
            return null;
        }
        byte version = buffer.get();
        if (version != CURSOR_VERSION) {
            return null;
        }
        int payloadStart = buffer.position();
        buffer.getInt(); // slotStart
        buffer.getInt(); // slotEnd
        int keyLength = buffer.getInt();
        byte[] lastKey = new byte[keyLength];
        buffer.get(lastKey);
        long lastVersion = buffer.getLong();
        long checkpointOffset = buffer.getLong();
        long copiedEntries = buffer.getLong();
        long copiedBytes = buffer.getLong();
        MigrationState state = MigrationState.values()[buffer.get()];
        int payloadEnd = buffer.position();
        int expectedCrc = buffer.getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, payloadStart, payloadEnd - payloadStart);
        if (crc.getValue() != (expectedCrc & 0xffffffffL)) {
            return null;
        }
        return new CursorFile(lastKey, lastVersion, checkpointOffset,
                copiedEntries, copiedBytes, state);
    }

    private static String cursorFile(int slotStart) {
        return "slot-" + slotStart + ".cursor";
    }

    private static int compare(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    private record CrcCount(long count, long checksum) {
    }

    private record CursorFile(byte[] lastKey, long lastVersion, long checkpointOffset,
                              long copiedEntries, long copiedBytes, MigrationState state) {
        private CursorFile {
            lastKey = lastKey.clone();
        }
    }
}
