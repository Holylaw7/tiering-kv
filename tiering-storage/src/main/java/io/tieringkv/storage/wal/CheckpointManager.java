package io.tieringkv.storage.wal;

import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Checkpoint 管理（ADR-0016 + v6.5.0 VERSION=2 分片位点向量与整文件 CRC32C 校验）：
 * MemTable 快照 + 单 WAL 位点（v1）或分片位点向量（v2），缩短恢复窗口。
 * 恢复顺序：载入快照 → 按位点向量并行重放各分片剩余 WAL。
 */
public final class CheckpointManager {

    public static final int MAGIC = 0x434B5054; // "CKPT"
    public static final byte VERSION_V1 = 1;
    public static final byte VERSION_V2 = 2;
    public static final byte VERSION = VERSION_V1;

    private CheckpointManager() {
    }

    public record Checkpoint(
            long segmentSequence,
            long offset,
            List<WALManager.WALPosition> shardPositions,
            List<KeyValueEntry> entries) {

        /** 向后兼容 3 参构造（单 WAL v1 语义）。 */
        public Checkpoint(long segmentSequence, long offset, List<KeyValueEntry> entries) {
            this(segmentSequence, offset,
                    List.of(new WALManager.WALPosition(segmentSequence, offset)),
                    entries);
        }

        public boolean isSharded() {
            return shardPositions != null && shardPositions.size() > 1;
        }
    }

    /** 写入单 WAL checkpoint（v1 兼容格式）。 */
    public static void write(
            Path walDirectory,
            WALManager.WALPosition position,
            List<KeyValueEntry> entries) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(estimateSizeV1(entries)).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.put(VERSION_V1);
        buffer.putLong(position.sequence());
        buffer.putLong(position.offset());
        putEntries(buffer, entries);
        writeAtomically(walDirectory, buffer.array());
    }

    /** 写入多分片 WAL checkpoint（v2 格式：包含全部分片安全低水位向量 + 整文件尾部 CRC32C）。 */
    public static void writeSharded(
            Path walDirectory,
            List<WALManager.WALPosition> positions,
            List<KeyValueEntry> entries) throws IOException {
        if (positions == null || positions.isEmpty()) {
            throw new IllegalArgumentException("positions must not be empty");
        }
        int totalSize = estimateSizeV2(positions.size(), entries);
        ByteBuffer buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.put(VERSION_V2);
        buffer.putInt(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            WALManager.WALPosition pos = positions.get(i);
            buffer.putInt(i);
            buffer.putLong(pos.sequence());
            buffer.putLong(pos.offset());
        }
        putEntries(buffer, entries);
        int payloadLen = buffer.position();
        long crc = ChecksumValidator.crc32c(buffer.array(), payloadLen);
        buffer.putLong(crc);
        writeAtomically(walDirectory, buffer.array());
    }

    private static void putEntries(ByteBuffer buffer, List<KeyValueEntry> entries) {
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
    }

    private static void writeAtomically(Path walDirectory, byte[] bytes) throws IOException {
        Files.createDirectories(walDirectory);
        Path tmp = walDirectory.resolve("checkpoint.tmp");
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer src = ByteBuffer.wrap(bytes);
            while (src.hasRemaining()) {
                ch.write(src);
            }
            ch.force(true);
        }
        Path target = walDirectory.resolve("checkpoint.bin");
        try {
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 读取 checkpoint（兼容 v1 与 v2）；缺失、截断或校验失败返回 null（触发全量重放）。 */
    public static Checkpoint read(Path walDirectory) {
        Path path = walDirectory.resolve("checkpoint.bin");
        if (!Files.exists(path)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length < 5) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            if (buffer.getInt() != MAGIC) {
                return null;
            }
            byte version = buffer.get();
            if (version == VERSION_V1) {
                long sequence = buffer.getLong();
                long offset = buffer.getLong();
                List<KeyValueEntry> entries = readEntries(buffer);
                if (entries == null) {
                    return null;
                }
                return new Checkpoint(sequence, offset, entries);
            }
            if (version == VERSION_V2) {
                if (bytes.length < 5 + 4 + 4 + 8) {
                    return null;
                }
                int payloadLen = bytes.length - 8;
                long expectedCrc = buffer.getLong(payloadLen);
                if (!ChecksumValidator.matches(bytes, payloadLen, expectedCrc)) {
                    return null;
                }
                buffer.limit(payloadLen);
                int shardCount = buffer.getInt();
                if (shardCount <= 0 || shardCount > WALConfig.MAX_SHARDS) {
                    return null;
                }
                List<WALManager.WALPosition> positions = new ArrayList<>(shardCount);
                for (int i = 0; i < shardCount; i++) {
                    int shardId = buffer.getInt();
                    long seq = buffer.getLong();
                    long off = buffer.getLong();
                    if (shardId != i) {
                        return null;
                    }
                    positions.add(new WALManager.WALPosition(seq, off));
                }
                List<KeyValueEntry> entries = readEntries(buffer);
                if (entries == null) {
                    return null;
                }
                WALManager.WALPosition first = positions.get(0);
                return new Checkpoint(first.sequence(), first.offset(), List.copyOf(positions), entries);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<KeyValueEntry> readEntries(ByteBuffer buffer) {
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
            byte[] value = new byte[valueLength];
            buffer.get(value);
            long expire = buffer.getLong();
            long create = buffer.getLong();
            long update = buffer.getLong();
            long version = buffer.getLong();
            entries.add(new KeyValueEntry(key, value, create, update, expire, version, false,
                    KeyValueEntry.sizeOf(key, value)));
        }
        return entries;
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

    private static int estimateSizeV1(List<KeyValueEntry> entries) {
        long size = 4 + 1 + 8 + 8 + 4 + entriesPayloadSize(entries);
        return (int) Math.min(size, Integer.MAX_VALUE);
    }

    private static int estimateSizeV2(int shardCount, List<KeyValueEntry> entries) {
        long size = 4 + 1 + 4 + (long) shardCount * (4 + 8 + 8) + 4 + entriesPayloadSize(entries) + 8;
        return (int) Math.min(size, Integer.MAX_VALUE);
    }

    private static long entriesPayloadSize(List<KeyValueEntry> entries) {
        long size = 0;
        for (KeyValueEntry entry : entries) {
            size += 4 + entry.key().length + 4
                    + (entry.value() == null ? 0 : entry.value().length)
                    + 8 + 8 + 8 + 8;
        }
        return size;
    }
}
