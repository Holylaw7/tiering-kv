package io.tieringkv.cluster.raft.snapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32C;

/** 快照读取与校验（ADR-0040）。 */
public final class SnapshotReader {

    private SnapshotReader() {
    }

    /** 读取快照；文件缺失返回 null，CRC/格式损坏抛出异常。 */
    public static Snapshot read(Path dir) throws IOException {
        Path file = dir.resolve(SnapshotWriter.FILE_NAME);
        if (!Files.exists(file)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 4 + 1 + 8 + 8 + 4 + 4) {
            throw new IOException("snapshot file too small: " + file);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != SnapshotWriter.MAGIC) {
            throw new IOException("snapshot bad magic: " + file);
        }
        int payloadStart = buffer.position();
        byte version = buffer.get();
        if (version != SnapshotWriter.VERSION) {
            throw new IOException("snapshot unsupported version: " + version);
        }
        long lastIncludedIndex = buffer.getLong();
        long lastIncludedTerm = buffer.getLong();
        int stateLength = buffer.getInt();
        if (stateLength < 0 || buffer.remaining() < stateLength + 4) {
            throw new IOException("snapshot bad state length: " + stateLength);
        }
        byte[] state = new byte[stateLength];
        buffer.get(state);
        int payloadEnd = buffer.position();
        int expectedCrc = buffer.getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, payloadStart, payloadEnd - payloadStart);
        if (crc.getValue() != (expectedCrc & 0xffffffffL)) {
            throw new IOException("snapshot crc mismatch: " + file);
        }
        return new Snapshot(new SnapshotMetadata(lastIncludedIndex, lastIncludedTerm), state);
    }

    public record Snapshot(SnapshotMetadata metadata, byte[] state) {
        public Snapshot {
            state = state.clone();
        }

        @Override
        public byte[] state() {
            return state.clone();
        }
    }
}
