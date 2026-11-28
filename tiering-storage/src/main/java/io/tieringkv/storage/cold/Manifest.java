package io.tieringkv.storage.cold;

import io.tieringkv.storage.wal.CheckpointManager;
import io.tieringkv.storage.wal.ChecksumValidator;

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
 * SSTable 清单（ADR-0017 + Wave 3C K1 v2 clearEpoch 与 fsync 加固）：
 * <ul>
 *   <li>v1（只读向后兼容，默认 {@code clearEpoch = 0}）：{@code [MAGIC 4B][VER=1 1B][COUNT 4B][CRC32C 8B][tables...]}；</li>
 *   <li>v2（当前落盘格式）：{@code [MAGIC 4B][VER=2 1B][CRC32C 8B][CLEAR_EPOCH 8B][COUNT 4B][tables...]}，
 *       CRC32C 完整覆盖 {@code clearEpoch + count + tables}；</li>
 *   <li>落盘严格遵循 {@code manifest.tmp} 写盘 → {@code FileChannel.force(true)} → {@code ATOMIC_MOVE} → 父目录 {@code fsync}。</li>
 * </ul>
 */
public final class Manifest {

    public static final int MAGIC = 0x544B4D4E; // "TKMN"
    public static final byte VERSION = 1;
    public static final byte VERSION_V2 = 2;
    public static final int HEADER_SIZE = 17;
    public static final int HEADER_SIZE_V2 = 25;
    private static final int V2_CRC_OFFSET = 5;
    private static final int V2_PAYLOAD_OFFSET = 13;

    private Manifest() {
    }

    /** Manifest 持久化视图（含冷层已收敛的 clearEpoch 与 SSTable 列表）。 */
    public record ManifestState(long clearEpoch, List<SSTableMeta> tables) {
    }

    public static void write(Path directory, List<SSTableMeta> tables) throws IOException {
        write(directory, 0L, tables);
    }

    public static void write(Path directory, long clearEpoch, List<SSTableMeta> tables) throws IOException {
        if (clearEpoch < 0L) {
            throw new IllegalArgumentException("clearEpoch must be >= 0: " + clearEpoch);
        }
        int size = HEADER_SIZE_V2;
        for (SSTableMeta table : tables) {
            size += 8 + 8 + 8 + 4 + table.firstKey().length + 4 + table.lastKey().length
                    + 4 + table.fileName().length();
        }
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.put(VERSION_V2);
        buffer.putLong(0L); // CRC32C 占位
        buffer.putLong(clearEpoch);
        buffer.putInt(tables.size());
        for (SSTableMeta table : tables) {
            buffer.putLong(table.id());
            buffer.putLong(table.entryCount());
            buffer.putLong(table.fileSize());
            buffer.putInt(table.firstKey().length);
            buffer.put(table.firstKey());
            buffer.putInt(table.lastKey().length);
            buffer.put(table.lastKey());
            buffer.putInt(table.fileName().length());
            buffer.put(table.fileName().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        long crc = ChecksumValidator.crc32c(buffer.array(), V2_PAYLOAD_OFFSET, size - V2_PAYLOAD_OFFSET);
        buffer.putLong(V2_CRC_OFFSET, crc);

        Files.createDirectories(directory);
        Path tmp = directory.resolve("manifest.tmp");
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer src = ByteBuffer.wrap(buffer.array());
            while (src.hasRemaining()) {
                ch.write(src);
            }
            ch.force(true);
        }
        Path target = directory.resolve("manifest.bin");
        try {
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        CheckpointManager.fsyncDirectory(directory);
    }

    public static List<SSTableMeta> read(Path directory) throws IOException {
        return readState(directory).tables();
    }

    public static ManifestState readState(Path directory) throws IOException {
        Path path = directory.resolve("manifest.bin");
        if (!Files.exists(path)) {
            return new ManifestState(0L, List.of());
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < HEADER_SIZE) {
            throw new ColdCorruptionException("manifest too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC) {
            throw new ColdCorruptionException("bad manifest header");
        }
        byte version = buffer.get();
        if (version == VERSION) {
            int count = buffer.getInt();
            long expected = buffer.getLong();
            if (count < 0 || !ChecksumValidator.matches(bytes, HEADER_SIZE, bytes.length - HEADER_SIZE, expected)) {
                throw new ColdCorruptionException("manifest checksum mismatch");
            }
            return new ManifestState(0L, readTables(buffer, count));
        }
        if (version == VERSION_V2) {
            if (bytes.length < HEADER_SIZE_V2) {
                throw new ColdCorruptionException("manifest v2 too short");
            }
            long expected = buffer.getLong();
            if (!ChecksumValidator.matches(bytes, V2_PAYLOAD_OFFSET, bytes.length - V2_PAYLOAD_OFFSET, expected)) {
                throw new ColdCorruptionException("manifest checksum mismatch");
            }
            long clearEpoch = buffer.getLong();
            int count = buffer.getInt();
            if (clearEpoch < 0L || count < 0) {
                throw new ColdCorruptionException("bad manifest v2 metadata");
            }
            return new ManifestState(clearEpoch, readTables(buffer, count));
        }
        throw new ColdCorruptionException("bad manifest header");
    }

    private static List<SSTableMeta> readTables(ByteBuffer buffer, int count) {
        List<SSTableMeta> tables = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long id = buffer.getLong();
            long entryCount = buffer.getLong();
            long fileSize = buffer.getLong();
            byte[] firstKey = readBytes(buffer);
            byte[] lastKey = readBytes(buffer);
            byte[] name = readBytes(buffer);
            tables.add(new SSTableMeta(id, new String(name, java.nio.charset.StandardCharsets.US_ASCII),
                    entryCount, fileSize, firstKey, lastKey));
        }
        return tables;
    }

    private static byte[] readBytes(ByteBuffer buffer) {
        int length = buffer.getInt();
        byte[] data = new byte[length];
        buffer.get(data);
        return data;
    }
}
