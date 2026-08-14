package io.tieringkv.replication.cross;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32C;

/**
 * 跨集群复制目标端水位（ADR-0321 M3 收尾）：按 (regionId, seq) 记录
 * 已应用水位，原子落盘，重启后跳过已应用事件（幂等续传）。
 *
 * <p>文件格式：MAGIC("TKW1") + count u32 + {regionIdLen u16 + regionId
 * + seq i64}[] + CRC32C u32。
 */
public final class CrossClusterWatermark implements AutoCloseable {

    private static final byte[] MAGIC = {'T', 'K', 'W', '1'};

    private final Map<String, Long> watermarks =
            new ConcurrentHashMap<>();
    private final Path file;

    public CrossClusterWatermark(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("file required");
        }
        this.file = file;
    }

    public boolean shouldSkip(String regionId, long seq) {
        return watermarks.getOrDefault(regionId, -1L) >= seq;
    }

    public void record(String regionId, long seq) {
        watermarks.merge(regionId, seq, Math::max);
    }

    public long watermark(String regionId) {
        return watermarks.getOrDefault(regionId, -1L);
    }

    public int size() {
        return watermarks.size();
    }

    public synchronized void checkpoint() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC);
        out.writeInt(watermarks.size());
        for (Map.Entry<String, Long> entry : watermarks.entrySet()) {
            byte[] regionId = entry.getKey()
                    .getBytes(StandardCharsets.UTF_8);
            out.writeShort(regionId.length);
            out.write(regionId);
            out.writeLong(entry.getValue());
        }
        out.flush();
        byte[] payload = bytes.toByteArray();
        CRC32C crc = new CRC32C();
        crc.update(payload);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        DataOutputStream tail = new DataOutputStream(all);
        tail.write(payload);
        tail.writeInt((int) crc.getValue());
        tail.flush();

        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = absolute.resolveSibling(absolute.getFileName()
                + ".tmp");
        byte[] encoded = all.toByteArray();
        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    public static CrossClusterWatermark load(Path file)
            throws IOException {
        if (!Files.exists(file)) {
            return new CrossClusterWatermark(file);
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 4 + 4 + 4) {
            throw new IOException("watermark file too short");
        }
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - 4);
        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(bytes));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1]
                || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
            throw new IOException("invalid watermark magic");
        }
        int count = in.readInt();
        if (count < 0 || count > 1_000_000) {
            throw new IOException("invalid watermark count " + count);
        }
        CrossClusterWatermark loaded = new CrossClusterWatermark(file);
        for (int i = 0; i < count; i++) {
            int len = in.readUnsignedShort();
            byte[] regionBytes = new byte[len];
            in.readFully(regionBytes);
            String regionId = new String(regionBytes,
                    StandardCharsets.UTF_8);
            long seq = in.readLong();
            loaded.record(regionId, seq);
        }
        int expectedCrc = in.readInt();
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("watermark CRC mismatch");
        }
        return loaded;
    }

    @Override
    public void close() throws IOException {
        checkpoint();
    }
}
