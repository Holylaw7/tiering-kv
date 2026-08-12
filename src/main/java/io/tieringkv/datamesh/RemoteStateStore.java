package io.tieringkv.datamesh;

import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32C;

/** 远端物化状态存储（ADR-0179）：落盘 + 恢复 + 损坏回退。 */
public final class RemoteStateStore {

    private static final int MAGIC = 0x54535631; // 'TSV1'

    /** 持久化状态：快照 + 增量 key 值。 */
    public record PersistedState(String viewId, double value,
                                 long count, boolean stale,
                                 long refreshedAtMillis,
                                 Map<String, Double> keys) {

        public PersistedState {
            keys = Map.copyOf(keys);
        }
    }

    private final Path dir;

    public RemoteStateStore(Path dir) {
        this.dir = dir;
    }

    public void save(String viewId, RemoteSnapshot snapshot,
                     Map<String, Double> keys) {
        if (viewId == null || viewId.isBlank()) {
            throw new IllegalArgumentException(
                    "viewId required");
        }
        if (snapshot == null || keys == null) {
            throw new IllegalArgumentException(
                    "snapshot and keys required");
        }
        try {
            Files.createDirectories(dir);
            byte[] payload = encode(new PersistedState(viewId,
                    snapshot.value(), snapshot.count(),
                    snapshot.stale(), snapshot.refreshedAtMillis(),
                    keys));
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(
                            file(viewId))))) {
                out.writeInt(MAGIC);
                out.writeInt(payload.length);
                out.write(payload);
                CRC32C crc = new CRC32C();
                crc.update(payload);
                out.writeInt((int) crc.getValue());
                out.flush();
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "failed to persist state", e);
        }
    }

    /** 恢复：缺失或损坏返回 empty（调用方回退全量刷新）。 */
    public Optional<PersistedState> load(String viewId) {
        if (!Files.exists(file(viewId))) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(
                        Files.newInputStream(file(viewId))))) {
            if (in.readInt() != MAGIC) {
                return Optional.empty();
            }
            int length = in.readInt();
            byte[] payload = new byte[length];
            in.readFully(payload);
            CRC32C crc = new CRC32C();
            crc.update(payload);
            if (in.readInt() != (int) crc.getValue()) {
                return Optional.empty();
            }
            return Optional.of(decode(payload));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void delete(String viewId) {
        try {
            Files.deleteIfExists(file(viewId));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "failed to delete state", e);
        }
    }

    private Path file(String viewId) {
        return dir.resolve(viewId + ".state");
    }

    private static byte[] encode(PersistedState state)
            throws IOException {
        java.io.ByteArrayOutputStream bytes =
                new java.io.ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(state.viewId());
            out.writeDouble(state.value());
            out.writeLong(state.count());
            out.writeBoolean(state.stale());
            out.writeLong(state.refreshedAtMillis());
            out.writeInt(state.keys().size());
            for (Map.Entry<String, Double> entry
                    : state.keys().entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeDouble(entry.getValue());
            }
            out.flush();
        }
        return bytes.toByteArray();
    }

    private static PersistedState decode(byte[] payload)
            throws IOException {
        try (DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(payload))) {
            String viewId = in.readUTF();
            double value = in.readDouble();
            long count = in.readLong();
            boolean stale = in.readBoolean();
            long refreshedAt = in.readLong();
            int keyCount = in.readInt();
            Map<String, Double> keys = new ConcurrentHashMap<>();
            for (int i = 0; i < keyCount; i++) {
                keys.put(in.readUTF(), in.readDouble());
            }
            return new PersistedState(viewId, value, count, stale,
                    refreshedAt, keys);
        }
    }
}
