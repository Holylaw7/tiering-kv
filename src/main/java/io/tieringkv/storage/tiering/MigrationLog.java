package io.tieringkv.storage.tiering;

import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.wal.ChecksumValidator;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迁移持久化日志（ADR-0022）：`migration/migration.log` 追加记录任务状态；
 * 启动恢复未完成任务；完成后压缩为仅未完成任务。
 */
public final class MigrationLog implements AutoCloseable {

    public static final int MAGIC = 0x544B4D47; // "TKMG"
    public static final byte VERSION = 1;

    private final Path path;
    private OutputStream out;

    public MigrationLog(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.path = directory.resolve("migration.log");
        this.out = new BufferedOutputStream(new FileOutputStream(path.toFile(), true), 64 * 1024);
    }

    public synchronized void append(MigrationTask task) throws IOException {
        byte[] key = task.key();
        byte[] target = task.target().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.allocate(4 + 1 + 1 + 4 + key.length + 8 + 4 + 4
                + target.length + 8).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.put(VERSION);
        buffer.put(statusByte(task.status()));
        buffer.putInt(key.length);
        buffer.put(key);
        buffer.putLong(task.version());
        buffer.putInt(task.retryCount());
        buffer.putInt(target.length);
        buffer.put(target);
        long crc = ChecksumValidator.crc32c(buffer.array(), 0, buffer.position());
        buffer.putLong(crc);
        out.write(buffer.array());
    }

    public synchronized void flush() throws IOException {
        out.flush();
    }

    /** 扫描日志，按 key+version 取最新状态；返回未完成任务（不含 SUCCESS/FAILED）。 */
    public synchronized List<MigrationTask> recover() throws IOException {
        out.flush();
        if (!Files.exists(path)) {
            return List.of();
        }
        byte[] bytes = Files.readAllBytes(path);
        Map<KeyIdentity, MigrationTask> latest = new LinkedHashMap<>();
        int offset = 0;
        while (offset < bytes.length) {
            int recordLength;
            try {
                recordLength = decodeRecord(bytes, offset, latest);
            } catch (RuntimeException e) {
                break; // 损坏尾部：停止（与 WAL 恢复同策略）
            }
            offset += recordLength;
        }
        List<MigrationTask> unfinished = new ArrayList<>();
        for (MigrationTask task : latest.values()) {
            if (task.status() != MigrationTask.Status.SUCCESS
                    && task.status() != MigrationTask.Status.FAILED) {
                unfinished.add(task);
            }
        }
        return unfinished;
    }

    /** 压缩日志：仅保留给定任务（启动恢复完成后调用）。 */
    public synchronized void compact(List<MigrationTask> keep) throws IOException {
        out.flush();
        out.close();
        Path tmp = path.resolveSibling("migration.tmp");
        try (OutputStream fresh = new BufferedOutputStream(
                new FileOutputStream(tmp.toFile(), false), 64 * 1024)) {
            for (MigrationTask task : keep) {
                byte[] key = task.key();
                byte[] target = task.target().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                ByteBuffer buffer = ByteBuffer.allocate(4 + 1 + 1 + 4 + key.length + 8 + 4 + 4
                        + target.length + 8).order(ByteOrder.BIG_ENDIAN);
                buffer.putInt(MAGIC);
                buffer.put(VERSION);
                buffer.put(statusByte(task.status()));
                buffer.putInt(key.length);
                buffer.put(key);
                buffer.putLong(task.version());
                buffer.putInt(task.retryCount());
                buffer.putInt(target.length);
                buffer.put(target);
                long crc = ChecksumValidator.crc32c(buffer.array(), 0, buffer.position());
                buffer.putLong(crc);
                fresh.write(buffer.array());
            }
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        this.out = new BufferedOutputStream(new FileOutputStream(path.toFile(), true), 64 * 1024);
    }

    @Override
    public synchronized void close() throws IOException {
        if (out != null) {
            out.flush();
            out.close();
            out = null;
        }
    }

    private static int decodeRecord(byte[] bytes, int offset, Map<KeyIdentity, MigrationTask> latest) {
        if (offset + 4 > bytes.length) {
            throw new IllegalArgumentException("truncated header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, bytes.length - offset)
                .order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC || buffer.get() != VERSION) {
            throw new IllegalArgumentException("bad header");
        }
        byte statusByte = buffer.get();
        int keyLength = buffer.getInt();
        if (keyLength < 0 || offset + 18 + keyLength > bytes.length) {
            throw new IllegalArgumentException("truncated record");
        }
        byte[] key = new byte[keyLength];
        buffer.get(key);
        long version = buffer.getLong();
        int retryCount = buffer.getInt();
        int targetLength = buffer.getInt();
        if (targetLength < 0 || buffer.position() + targetLength + 8 > bytes.length) {
            throw new IllegalArgumentException("truncated record");
        }
        byte[] targetBytes = new byte[targetLength];
        buffer.get(targetBytes);
        long expected = buffer.getLong();
        int recordLength = 4 + 1 + 1 + 4 + keyLength + 8 + 4 + 4 + targetLength + 8;
        if (!ChecksumValidator.matches(bytes, offset, recordLength - 8, expected)) {
            throw new IllegalArgumentException("checksum mismatch");
        }
        KeyValueEntry entry = new KeyValueEntry(key, null, 0, 0, -1, version, false,
                KeyValueEntry.sizeOf(key, null));
        MigrationTask task = new MigrationTask(entry, "memory",
                new String(targetBytes, java.nio.charset.StandardCharsets.US_ASCII),
                retryCount, statusOf(statusByte));
        latest.put(new KeyIdentity(ByteBuffer.wrap(key), version), task);
        return recordLength;
    }

    private static byte statusByte(MigrationTask.Status status) {
        return switch (status) {
            case PENDING -> 1;
            case RUNNING -> 2;
            case SUCCESS -> 3;
            case FAILED -> 4;
            case RETRY -> 5;
        };
    }

    private static MigrationTask.Status statusOf(byte value) {
        return switch (value) {
            case 1 -> MigrationTask.Status.PENDING;
            case 2 -> MigrationTask.Status.RUNNING;
            case 3 -> MigrationTask.Status.SUCCESS;
            case 4 -> MigrationTask.Status.FAILED;
            case 5 -> MigrationTask.Status.RETRY;
            default -> throw new IllegalArgumentException("bad status");
        };
    }

    private record KeyIdentity(ByteBuffer key, long version) {
    }
}
