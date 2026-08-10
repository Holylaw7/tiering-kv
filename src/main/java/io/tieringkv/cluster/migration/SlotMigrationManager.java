package io.tieringkv.cluster.migration;

import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.cluster.sharding.SlotTable;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32C;

/**
 * 在线 Slot 迁移（ADR-0043）：
 * INIT → COPYING（有序复制 + checkpoint）→ VERIFYING（CRC 校验）
 * → SWITCHING（SlotTable 原子切换）→ DONE（清理源）；失败/中断可从
 * checkpoint 续传。
 */
public final class SlotMigrationManager {

    private static final int CHECKPOINT_MAGIC = 0x4D434B50; // 'MCKP'
    private static final byte CHECKPOINT_VERSION = 1;

    private final SlotTable slotTable;
    private final Path checkpointDir;

    public SlotMigrationManager(SlotTable slotTable, Path checkpointDir) {
        this.slotTable = slotTable;
        this.checkpointDir = checkpointDir;
    }

    public MigrationState start(MigrationTask task) throws IOException {
        synchronized (task.lock()) {
            task.state(MigrationState.COPYING);
            task.checkpoint(MigrationCheckpoint.empty()
                    .withState(MigrationState.COPYING));
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

    /** 从持久化 checkpoint 恢复未完成任务。 */
    public MigrationTask resume(MigrationTask task) throws IOException {
        synchronized (task.lock()) {
            MigrationCheckpoint saved = load(task.taskId());
            if (saved != null) {
                task.checkpoint(saved);
                task.state(saved.state() == MigrationState.DONE
                        ? MigrationState.DONE : MigrationState.COPYING);
                if (saved.state() == MigrationState.VERIFYING) {
                    task.state(MigrationState.VERIFYING);
                }
            }
            return task;
        }
    }

    public MigrationCheckpoint checkpoint(MigrationTask task) {
        return task.checkpoint();
    }

    private void copyBatch(MigrationTask task, int batchSize) throws IOException {
        MigrationCheckpoint checkpoint = task.checkpoint();
        long entries = checkpoint.copiedEntries();
        long bytes = checkpoint.copiedBytes();
        byte[] lastKey = checkpoint.lastKey();
        int copied = 0;
        boolean completed = true;
        try (StorageIterator iterator = task.source().iterator()) {
            while (iterator.hasNext() && copied < batchSize) {
                KeyValueEntry entry = iterator.next();
                if (!inRange(task, entry.key()) || compare(entry.key(), lastKey) <= 0) {
                    continue;
                }
                long ttl = entry.expireTimestamp() >= 0
                        ? Math.max(0, entry.expireTimestamp() - System.currentTimeMillis())
                        : StorageEngine.NO_TTL;
                task.target().put(entry.key(), entry.value(), ttl);
                lastKey = entry.key();
                entries++;
                bytes += entry.size();
                copied++;
            }
            if (iterator.hasNext()) {
                completed = false;
            }
        }
        MigrationCheckpoint updated = new MigrationCheckpoint(
                lastKey, entries, bytes, 0, MigrationState.COPYING);
        task.checkpoint(updated);
        if (completed) {
            task.state(MigrationState.VERIFYING);
            task.checkpoint(updated.withState(MigrationState.VERIFYING));
        }
        persist(task);
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
        // 切换后清理源数据（先切换、后删除，避免流量指向空目标）
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
        ByteBuffer payload = ByteBuffer.allocate(4 + 4 + 4 + checkpoint.lastKey().length
                + 8 + 8 + 8 + 1).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(task.slotStart());
        payload.putInt(task.slotEnd());
        payload.putInt(checkpoint.lastKey().length);
        payload.put(checkpoint.lastKey());
        payload.putLong(checkpoint.copiedEntries());
        payload.putLong(checkpoint.copiedBytes());
        payload.putLong(checkpoint.checksum());
        payload.put((byte) checkpoint.state().ordinal());
        byte[] payloadBytes = payload.array();
        CRC32C crc = new CRC32C();
        crc.update(payloadBytes);

        ByteBuffer out = ByteBuffer.allocate(4 + 1 + payloadBytes.length + 4)
                .order(ByteOrder.BIG_ENDIAN);
        out.putInt(CHECKPOINT_MAGIC);
        out.put(CHECKPOINT_VERSION);
        out.put(payloadBytes);
        out.putInt((int) crc.getValue());
        Files.write(checkpointDir.resolve("checkpoint-" + task.taskId() + ".bin"),
                out.array());
    }

    private MigrationCheckpoint load(String taskId) throws IOException {
        Path file = checkpointDir.resolve("checkpoint-" + taskId + ".bin");
        if (!Files.exists(file)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != CHECKPOINT_MAGIC) {
            return null;
        }
        byte version = buffer.get();
        if (version != CHECKPOINT_VERSION) {
            return null;
        }
        int payloadStart = buffer.position();
        buffer.getInt(); // slotStart
        buffer.getInt(); // slotEnd
        int keyLength = buffer.getInt();
        byte[] lastKey = new byte[keyLength];
        buffer.get(lastKey);
        long entries = buffer.getLong();
        long copiedBytes = buffer.getLong();
        long checksum = buffer.getLong();
        MigrationState state = MigrationState.values()[buffer.get()];
        int payloadEnd = buffer.position();
        int expectedCrc = buffer.getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, payloadStart, payloadEnd - payloadStart);
        if (crc.getValue() != (expectedCrc & 0xffffffffL)) {
            return null;
        }
        return new MigrationCheckpoint(lastKey, entries, copiedBytes, checksum, state);
    }

    private static int compare(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    private record CrcCount(long count, long checksum) {
    }
}
