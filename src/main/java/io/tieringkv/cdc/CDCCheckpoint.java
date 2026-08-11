package io.tieringkv.cdc;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32C;

/** CDC 消费检查点（ADR-0105）：持久化已消费 seq，崩溃后从 seq+1 恢复。 */
public final class CDCCheckpoint {

    private static final int MAGIC = 0x4344434B; // 'CDCK'
    private static final String FILE = "cdc-checkpoint.bin";

    private final Path file;
    private long seq = -1; // -1 = 尚未消费任何事件

    private CDCCheckpoint(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            load();
        }
    }

    public static CDCCheckpoint open(Path dir) throws IOException {
        Files.createDirectories(dir);
        return new CDCCheckpoint(dir.resolve(FILE));
    }

    public synchronized void advance(long seq) throws IOException {
        this.seq = seq;
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(MAGIC);
            out.writeLong(seq);
            CRC32C crc = new CRC32C();
            crc.update(java.nio.ByteBuffer.allocate(8).putLong(seq).flip());
            out.writeInt((int) crc.getValue());
            out.flush();
        }
    }

    public synchronized long seq() {
        return seq;
    }

    private void load() throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("bad cdc checkpoint magic");
            }
            seq = in.readLong();
            CRC32C crc = new CRC32C();
            crc.update(java.nio.ByteBuffer.allocate(8).putLong(seq).flip());
            int expected = in.readInt();
            if (expected != (int) crc.getValue()) {
                throw new IOException("cdc checkpoint crc mismatch");
            }
        }
    }
}
