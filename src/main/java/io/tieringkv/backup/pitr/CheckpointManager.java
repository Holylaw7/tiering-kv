package io.tieringkv.backup.pitr;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** PITR 检查点（ADR-0104）：快照字节 + 已归档水位 + 时间戳。 */
public final class CheckpointManager {

    private static final int MAGIC = 0x43504B31; // 'CPK1'
    private static final String FILE = "checkpoint.bin";

    public record Checkpoint(long watermark, long timestamp,
                             byte[] snapshotBytes) {
    }

    private CheckpointManager() {
    }

    public static void save(Path dir, Checkpoint checkpoint)
            throws IOException {
        Files.createDirectories(dir);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(
                        dir.resolve(FILE))))) {
            out.writeInt(MAGIC);
            out.writeLong(checkpoint.watermark());
            out.writeLong(checkpoint.timestamp());
            out.writeInt(checkpoint.snapshotBytes().length);
            out.write(checkpoint.snapshotBytes());
            out.flush();
        }
    }

    public static Checkpoint load(Path dir) throws IOException {
        Path file = dir.resolve(FILE);
        if (!Files.exists(file)) {
            throw new IOException("checkpoint not found: " + file);
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("bad checkpoint magic");
            }
            long watermark = in.readLong();
            long timestamp = in.readLong();
            int length = in.readInt();
            byte[] snapshot = new byte[length];
            in.readFully(snapshot);
            return new Checkpoint(watermark, timestamp, snapshot);
        }
    }
}
