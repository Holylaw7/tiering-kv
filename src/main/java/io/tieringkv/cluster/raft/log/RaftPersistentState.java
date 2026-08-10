package io.tieringkv.cluster.raft.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

/**
 * Raft 持久状态（term / votedFor / commitIndex），二进制 + CRC32C。
 * 恢复时由 RaftNode 读取，避免重启后重复投票与错误提交。
 */
public final class RaftPersistentState implements AutoCloseable {

    private static final int MAGIC = 0x52535431; // 'RST1'
    private static final byte VERSION = 1;

    private final Path file;
    private final FileChannel channel;
    private long term;
    private String votedFor;
    private long commitIndex;
    private boolean closed;

    private RaftPersistentState(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            load();
        }
        this.channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    public static RaftPersistentState open(Path dir) throws IOException {
        Files.createDirectories(dir);
        return new RaftPersistentState(dir.resolve("raft.state"));
    }

    public synchronized void persist(long term, String votedFor, long commitIndex) {
        persist(term, votedFor, commitIndex, true);
    }

    /** force=false 时仅缓冲写入（commitIndex 可由日志重放推导，安全性由 log 保证）。 */
    public synchronized void persist(long term, String votedFor, long commitIndex,
                                     boolean force) {
        this.term = term;
        this.votedFor = votedFor;
        this.commitIndex = commitIndex;
        write(force);
    }

    public synchronized long term() {
        return term;
    }

    public synchronized String votedFor() {
        return votedFor;
    }

    public synchronized long commitIndex() {
        return commitIndex;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            channel.force(true);
            channel.close();
        } catch (IOException e) {
            throw new IllegalStateException("raft state close failed", e);
        }
    }

    private void write(boolean force) {
        byte[] votedBytes = votedFor == null
                ? new byte[0] : votedFor.getBytes(StandardCharsets.UTF_8);
        if (votedBytes.length > 0xFFFF) {
            throw new IllegalStateException("votedFor too long");
        }
        ByteBuffer payload = ByteBuffer.allocate(1 + 8 + 2 + votedBytes.length + 8)
                .order(ByteOrder.BIG_ENDIAN);
        payload.put(VERSION);
        payload.putLong(term);
        payload.putShort((short) votedBytes.length);
        payload.put(votedBytes);
        payload.putLong(commitIndex);
        byte[] payloadBytes = payload.array();
        CRC32C crc = new CRC32C();
        crc.update(payloadBytes);

        ByteBuffer out = ByteBuffer.allocate(4 + payloadBytes.length + 4)
                .order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC);
        out.put(payloadBytes);
        out.putInt((int) crc.getValue());
        try {
            if (closed) {
                throw new IllegalStateException("raft state already closed");
            }
            ByteBuffer buffer = ByteBuffer.wrap(out.array());
            channel.position(0);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.truncate(buffer.position());
            if (force) {
                channel.force(true);
            }
        } catch (IOException e) {
            throw new IllegalStateException("raft state persist failed", e);
        }
    }

    private void load() throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 4 + 1 + 8 + 2 + 8 + 4) {
            return; // 空/损坏文件按初始状态处理
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC) {
            return;
        }
        int payloadStart = buffer.position();
        byte version = buffer.get();
        if (version != VERSION) {
            return;
        }
        term = buffer.getLong();
        int votedLength = buffer.getShort() & 0xFFFF;
        byte[] votedBytes = new byte[votedLength];
        buffer.get(votedBytes);
        votedFor = votedLength == 0 ? null : new String(votedBytes, StandardCharsets.UTF_8);
        commitIndex = buffer.getLong();
        int payloadEnd = buffer.position();
        int expectedCrc = buffer.getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, payloadStart, payloadEnd - payloadStart);
        if (crc.getValue() != (expectedCrc & 0xffffffffL)) {
            term = 0;
            votedFor = null;
            commitIndex = -1;
        }
    }
}
