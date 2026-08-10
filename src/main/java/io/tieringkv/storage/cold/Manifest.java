package io.tieringkv.storage.cold;

import io.tieringkv.storage.wal.ChecksumValidator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * SSTable 清单（ADR-0017）：表元数据列表（创建序 = 旧→新），原子更新。
 * 缺失返回空表；损坏抛 {@link ColdCorruptionException}（启动即失败）。
 */
public final class Manifest {

    public static final int MAGIC = 0x544B4D4E; // "TKMN"
    public static final byte VERSION = 1;
    public static final int HEADER_SIZE = 17;

    private Manifest() {
    }

    public static void write(Path directory, List<SSTableMeta> tables) throws IOException {
        int size = HEADER_SIZE;
        for (SSTableMeta table : tables) {
            size += 8 + 8 + 8 + 4 + table.firstKey().length + 4 + table.lastKey().length
                    + 4 + table.fileName().length();
        }
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.put(VERSION);
        buffer.putInt(tables.size());
        buffer.putLong(0);
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
        long crc = ChecksumValidator.crc32c(buffer.array(), HEADER_SIZE, size - HEADER_SIZE);
        buffer.putLong(9, crc);
        Path tmp = directory.resolve("manifest.tmp");
        Files.write(tmp, buffer.array());
        Files.move(tmp, directory.resolve("manifest.bin"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static List<SSTableMeta> read(Path directory) throws IOException {
        Path path = directory.resolve("manifest.bin");
        if (!Files.exists(path)) {
            return List.of();
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < HEADER_SIZE) {
            throw new ColdCorruptionException("manifest too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC || buffer.get() != VERSION) {
            throw new ColdCorruptionException("bad manifest header");
        }
        int count = buffer.getInt();
        long expected = buffer.getLong();
        if (!ChecksumValidator.matches(bytes, HEADER_SIZE, bytes.length - HEADER_SIZE, expected)) {
            throw new ColdCorruptionException("manifest checksum mismatch");
        }
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
