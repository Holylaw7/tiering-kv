package io.tieringkv.cluster.migration.parallel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32C;

/** chunk 级检查点（ADR-0063）：lastKey/offset/checksum/状态 + CRC。 */
public final class ChunkCheckpoint {

    private static final int MAGIC = 0x43484B50; // 'CHKP'
    private static final byte VERSION = 1;

    public enum Status {
        PENDING,
        RUNNING,
        DONE,
        FAILED
    }

    private final int chunkId;
    private byte[] lastKey;
    private long offset;
    private long checksum;
    private Status status;

    public ChunkCheckpoint(int chunkId, byte[] lastKey,
                           long offset, long checksum, Status status) {
        this.chunkId = chunkId;
        this.lastKey = lastKey == null ? new byte[0] : lastKey.clone();
        this.offset = offset;
        this.checksum = checksum;
        this.status = status;
    }

    public static ChunkCheckpoint empty(int chunkId) {
        return new ChunkCheckpoint(chunkId, new byte[0], 0, 0, Status.PENDING);
    }

    public static ChunkCheckpoint load(Path dir, int chunkId) throws IOException {
        Path file = dir.resolve("chunk-" + chunkId + ".ckpt");
        if (!Files.exists(file)) {
            return empty(chunkId);
        }
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC || buffer.get() != VERSION) {
            return empty(chunkId);
        }
        int payloadStart = buffer.position();
        int id = buffer.getInt();
        int keyLength = buffer.getInt();
        byte[] lastKey = new byte[keyLength];
        buffer.get(lastKey);
        long offset = buffer.getLong();
        long checksum = buffer.getLong();
        Status status = Status.values()[buffer.get()];
        int payloadEnd = buffer.position();
        int expectedCrc = buffer.getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, payloadStart, payloadEnd - payloadStart);
        if (crc.getValue() != (expectedCrc & 0xffffffffL)) {
            return empty(chunkId);
        }
        return new ChunkCheckpoint(id, lastKey, offset, checksum, status);
    }

    public synchronized void persist(Path dir) throws IOException {
        Files.createDirectories(dir);
        ByteBuffer payload = ByteBuffer.allocate(
                4 + 4 + lastKey.length + 8 + 8 + 1)
                .order(ByteOrder.BIG_ENDIAN);
        payload.putInt(chunkId);
        payload.putInt(lastKey.length);
        payload.put(lastKey);
        payload.putLong(offset);
        payload.putLong(checksum);
        payload.put((byte) status.ordinal());
        byte[] payloadBytes = payload.array();
        CRC32C crc = new CRC32C();
        crc.update(payloadBytes);
        ByteBuffer out = ByteBuffer.allocate(4 + 1 + payloadBytes.length + 4)
                .order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC);
        out.put(VERSION);
        out.put(payloadBytes);
        out.putInt((int) crc.getValue());
        Files.write(dir.resolve("chunk-" + chunkId + ".ckpt"), out.array());
    }

    public int chunkId() {
        return chunkId;
    }

    public byte[] lastKey() {
        return lastKey.clone();
    }

    public long offset() {
        return offset;
    }

    public long checksum() {
        return checksum;
    }

    public Status status() {
        return status;
    }

    public synchronized void advance(byte[] key, long updatedChecksum) {
        this.lastKey = key == null ? new byte[0] : key.clone();
        this.offset++;
        this.checksum = updatedChecksum;
    }

    public synchronized void markRunning() {
        this.status = Status.RUNNING;
    }

    public synchronized void markDone() {
        this.status = Status.DONE;
    }

    public synchronized void markFailed() {
        this.status = Status.FAILED;
    }
}
