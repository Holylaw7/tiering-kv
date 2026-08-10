package io.tieringkv.cluster.raft.snapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

/**
 * 快照写入（ADR-0040）：
 * MAGIC(4B) | VERSION(1B) | LAST_INCLUDED_INDEX(8B) | LAST_INCLUDED_TERM(8B)
 * | STATE_LENGTH(4B) | STATE_DATA | CRC32C(4B)。
 * 通过临时文件 + 原子移动避免半写快照被当作有效文件。
 */
public final class SnapshotWriter {

    public static final int MAGIC = 0x534E4150; // 'SNAP'
    public static final byte VERSION = 1;
    public static final String FILE_NAME = "snapshot.latest";

    private SnapshotWriter() {
    }

    public static void write(Path dir, SnapshotMetadata metadata, byte[] state)
            throws IOException {
        Files.createDirectories(dir);
        ByteBuffer payload = ByteBuffer.allocate(1 + 8 + 8 + 4 + state.length)
                .order(ByteOrder.BIG_ENDIAN);
        payload.put(VERSION);
        payload.putLong(metadata.lastIncludedIndex());
        payload.putLong(metadata.lastIncludedTerm());
        payload.putInt(state.length);
        payload.put(state);
        byte[] payloadBytes = payload.array();
        CRC32C crc = new CRC32C();
        crc.update(payloadBytes);

        ByteBuffer out = ByteBuffer.allocate(4 + payloadBytes.length + 4)
                .order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC);
        out.put(payloadBytes);
        out.putInt((int) crc.getValue());

        Path tmp = dir.resolve(FILE_NAME + ".tmp");
        try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(out.array()));
            channel.force(true);
        }
        Files.move(tmp, dir.resolve(FILE_NAME),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
