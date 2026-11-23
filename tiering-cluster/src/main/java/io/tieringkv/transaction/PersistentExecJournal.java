package io.tieringkv.transaction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;

/**
 * 持久化 ExecJournal（ADR-0301）：追加日志 + CRC32C + 崩溃恢复；
 * additive 文件，不改 WAL/RPC 格式。
 */
public final class PersistentExecJournal {

    private static final int MAGIC = 0x54454A31;

    /** 文件记录。 */
    public record FileRecord(long txnId, int commandCount,
                             ExecJournal.Outcome outcome,
                             long timestampMillis) {
    }

    private final Path file;
    private final List<FileRecord> records =
            new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    public PersistentExecJournal(Path file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file required");
        }
        this.file = file;
        for (FileRecord record : load(file)) {
            records.add(record);
            sequence.accumulateAndGet(record.txnId(),
                    Math::max);
        }
    }

    public synchronized long append(int commandCount,
                                    ExecJournal.Outcome outcome)
            throws IOException {
        long txnId = sequence.incrementAndGet();
        long timestamp = System.currentTimeMillis();
        ByteBuffer buffer = ByteBuffer.allocate(4 + 8 + 4 + 1
                + 8);
        buffer.putInt(MAGIC);
        buffer.putLong(txnId);
        buffer.putInt(commandCount);
        buffer.put((byte) outcome.ordinal());
        buffer.putLong(timestamp);
        byte[] body = new byte[buffer.position()];
        buffer.flip();
        buffer.get(body);
        CRC32C crc = new CRC32C();
        crc.update(body);
        ByteBuffer record = ByteBuffer.allocate(
                body.length + 4 + 4);
        record.putInt(body.length + 4);
        record.put(body);
        record.putInt((int) crc.getValue());
        Files.write(file, record.array(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        records.add(new FileRecord(txnId, commandCount, outcome,
                timestamp));
        return txnId;
    }

    public List<FileRecord> records() {
        return List.copyOf(records);
    }

    public int size() {
        return records.size();
    }

    public static List<FileRecord> load(Path file)
            throws IOException {
        List<FileRecord> result = new ArrayList<>();
        if (!Files.exists(file)) {
            return result;
        }
        byte[] bytes = Files.readAllBytes(file);
        int offset = 0;
        while (offset + 4 <= bytes.length) {
            int length = ByteBuffer.wrap(bytes, offset, 4)
                    .getInt();
            if (length < 8 || offset + 4 + length > bytes.length) {
                break; // 截断尾部：忽略
            }
            byte[] body = java.util.Arrays.copyOfRange(bytes,
                    offset + 4, offset + 4 + length - 4);
            int storedCrc = ByteBuffer.wrap(bytes,
                    offset + 4 + length - 4, 4).getInt();
            CRC32C crc = new CRC32C();
            crc.update(body);
            if ((int) crc.getValue() != storedCrc) {
                break; // 损坏尾部：忽略
            }
            ByteBuffer parsed = ByteBuffer.wrap(body);
            if (parsed.getInt() != MAGIC) {
                break;
            }
            long txnId = parsed.getLong();
            int count = parsed.getInt();
            ExecJournal.Outcome outcome = ExecJournal.Outcome
                    .values()[parsed.get() & 0xff];
            long timestamp = parsed.getLong();
            result.add(new FileRecord(txnId, count, outcome,
                    timestamp));
            offset += 4 + length;
        }
        return result;
    }
}
