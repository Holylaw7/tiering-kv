package io.tieringkv.storage.wal;

import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Checkpoint 管理（ADR-0016）：MemTable 快照 + WAL offset，缩短恢复窗口。
 * 恢复顺序：载入快照 → 从 offset 重放剩余 WAL。
 */
public final class CheckpointManager {

    public static final int MAGIC = 0x434B5054; // "CKPT"
    public static final byte VERSION = 1;

    private CheckpointManager() {
    }

    public record Checkpoint(long segmentSequence, long offset, List<KeyValueEntry> entries) {
    }

    public static void write(
            Path walDirectory,
            WALManager.WALPosition position,
            List<KeyValueEntry> entries) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(estimateSize(entries)).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.put(VERSION);
        buffer.putLong(position.sequence());
        buffer.putLong(position.offset());
        buffer.putInt(entries.size());
        for (KeyValueEntry entry : entries) {
            buffer.putInt(entry.key().length);
            buffer.put(entry.key());
            byte[] value = entry.value() == null ? new byte[0] : entry.value();
            buffer.putInt(value.length);
            buffer.put(value);
            buffer.putLong(entry.expireTimestamp());
            buffer.putLong(entry.createTimestamp());
            buffer.putLong(entry.updateTimestamp());
            buffer.putLong(entry.version());
        }
        Path tmp = walDirectory.resolve("checkpoint.tmp");
        Files.write(tmp, buffer.array());
        Files.move(tmp, walDirectory.resolve("checkpoint.bin"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** 读取 checkpoint；缺失或损坏返回 null（触发全量重放）。 */
    public static Checkpoint read(Path walDirectory) {
        Path path = walDirectory.resolve("checkpoint.bin");
        if (!Files.exists(path)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            if (buffer.getInt() != MAGIC || buffer.get() != VERSION) {
                return null;
            }
            long sequence = buffer.getLong();
            long offset = buffer.getLong();
            int count = buffer.getInt();
            if (count < 0) {
                return null;
            }
            List<KeyValueEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int keyLength = buffer.getInt();
                byte[] key = new byte[keyLength];
                buffer.get(key);
                int valueLength = buffer.getInt();
                byte[] value = valueLength == 0 ? null : new byte[valueLength];
                if (value != null) {
                    buffer.get(value);
                }
                long expire = buffer.getLong();
                long create = buffer.getLong();
                long update = buffer.getLong();
                long version = buffer.getLong();
                entries.add(new KeyValueEntry(key, value, create, update, expire, version, false,
                        KeyValueEntry.sizeOf(key, value)));
            }
            return new Checkpoint(sequence, offset, entries);
        } catch (Exception e) {
            return null;
        }
    }

    /** 载入快照到 MemTable（过期键跳过）；随后由调用方重放剩余 WAL。 */
    public static void restore(MemTable memTable, Checkpoint checkpoint) {
        long now = System.currentTimeMillis();
        for (KeyValueEntry entry : checkpoint.entries()) {
            if (entry.expireTimestamp() >= 0) {
                long remaining = entry.expireTimestamp() - now;
                if (remaining <= 0) {
                    continue;
                }
                memTable.put(entry.key(), entry.value(), remaining);
            } else {
                memTable.put(entry.key(), entry.value());
            }
        }
    }

    private static int estimateSize(List<KeyValueEntry> entries) {
        long size = 4 + 1 + 8 + 8 + 4;
        for (KeyValueEntry entry : entries) {
            size += 4 + entry.key().length + 4
                    + (entry.value() == null ? 0 : entry.value().length)
                    + 8 + 8 + 8 + 8;
        }
        return (int) Math.min(size, Integer.MAX_VALUE);
    }
}
