package io.tieringkv.cluster.raft.log;

import io.tieringkv.cluster.raft.LogEntry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

/**
 * 文件型 RaftLog（ADR-0039）：分段追加 + CRC 校验 + 尾部截断恢复；
 * SYNC/ASYNC/NONE 耐久策略；空日志的 baseIndex 持久化到 base.idx。
 */
public final class FileRaftLog implements RaftLog {

    public static final String SEGMENT_PREFIX = "segment-";
    public static final String SEGMENT_SUFFIX = ".log";
    public static final String BASE_FILE = "base.idx";

    private static final long DEFAULT_MAX_SEGMENT_BYTES = 64L * 1024 * 1024;
    private static final int DEFAULT_MAX_SEGMENT_ENTRIES = 200_000;
    private static final long ASYNC_FLUSH_INTERVAL_MILLIS = 100;
    private static final int BASE_MAGIC = 0x52424153; // 'RBAS'

    private final Path dir;
    private final Durability durability;
    private final long maxSegmentBytes;
    private final int maxSegmentEntries;
    private final List<LogSegment> segments = new ArrayList<>();
    private final ScheduledExecutorService flusher;
    private long baseIndex;
    private volatile boolean closed;

    private FileRaftLog(Path dir, Durability durability,
                        long maxSegmentBytes, int maxSegmentEntries) throws IOException {
        this.dir = dir;
        this.durability = durability;
        this.maxSegmentBytes = maxSegmentBytes;
        this.maxSegmentEntries = maxSegmentEntries;
        RaftLogRecovery.Result result = RaftLogRecovery.recover(dir);
        segments.addAll(result.segments());
        baseIndex = loadBaseIndex();
        if (!segments.isEmpty() && segments.get(0).firstIndex() < baseIndex) {
            baseIndex = segments.get(0).firstIndex();
        }
        if (durability == Durability.ASYNC) {
            flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "raftlog-flush");
                thread.setDaemon(true);
                return thread;
            });
            flusher.scheduleWithFixedDelay(this::forceAll, ASYNC_FLUSH_INTERVAL_MILLIS,
                    ASYNC_FLUSH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        } else {
            flusher = null;
        }
    }

    public static FileRaftLog open(Path dir, Durability durability) throws IOException {
        return open(dir, durability, DEFAULT_MAX_SEGMENT_BYTES, DEFAULT_MAX_SEGMENT_ENTRIES);
    }

    public static FileRaftLog open(Path dir, Durability durability,
                                   long maxSegmentBytes, int maxSegmentEntries)
            throws IOException {
        return new FileRaftLog(dir, durability, maxSegmentBytes, maxSegmentEntries);
    }

    @Override
    public void append(LogEntry entry) {
        checkWritable();
        long expected = lastIndex() + 1;
        if (entry.index() != expected) {
            throw new IllegalArgumentException(
                    "out-of-order append: expected " + expected + " got " + entry.index());
        }
        try {
            LogSegment tail = segments.isEmpty() ? null : segments.get(segments.size() - 1);
            if (tail == null || tail.sizeBytes() >= maxSegmentBytes
                    || tail.size() >= maxSegmentEntries) {
                rotate(entry.index());
            }
            segments.get(segments.size() - 1).append(entry);
            if (durability == Durability.SYNC) {
                forceAll();
            }
        } catch (IOException e) {
            throw new IllegalStateException("raft log append failed", e);
        }
    }

    @Override
    public LogEntry entryAt(long index) {
        LogSegment segment = findSegment(index);
        if (segment == null) {
            throw new IllegalArgumentException("entry not found: " + index);
        }
        try {
            return segment.entryAt(index);
        } catch (IOException e) {
            throw new IllegalStateException("raft log read failed", e);
        }
    }

    @Override
    public List<LogEntry> entriesFrom(long from) {
        long start = Math.max(from, firstIndex());
        if (start > lastIndex()) {
            return List.of();
        }
        List<LogEntry> entries = new ArrayList<>();
        for (LogSegment segment : segments) {
            if (segment.lastIndex() < start) {
                continue;
            }
            for (long index = Math.max(start, segment.firstIndex());
                 index <= segment.lastIndex(); index++) {
                entries.add(entryAt(index));
            }
        }
        return entries;
    }

    @Override
    public long firstIndex() {
        return segments.isEmpty() ? baseIndex : segments.get(0).firstIndex();
    }

    @Override
    public long lastIndex() {
        return segments.isEmpty() ? baseIndex - 1 : segments.get(segments.size() - 1).lastIndex();
    }

    @Override
    public long lastTerm() {
        return segments.isEmpty() ? 0 : entryAt(lastIndex()).term();
    }

    @Override
    public long termAt(long index) {
        return entryAt(index).term();
    }

    @Override
    public int size() {
        int total = 0;
        for (LogSegment segment : segments) {
            total += segment.size();
        }
        return total;
    }

    @Override
    public void truncateFrom(long index) {
        checkWritable();
        try {
            if (index <= firstIndex()) {
                for (LogSegment segment : segments) {
                    segment.close();
                    Files.deleteIfExists(segment.file());
                }
                segments.clear();
                baseIndex = index;
                saveBaseIndex();
                return;
            }
            int cut = -1;
            for (int i = 0; i < segments.size(); i++) {
                if (segments.get(i).firstIndex() >= index) {
                    cut = i;
                    break;
                }
            }
            if (cut >= 0) {
                if (cut > 0) {
                    // 截断包含 index 的前一段，再删除其后所有段
                    segments.get(cut - 1).truncateTo(index);
                }
                for (int i = cut; i < segments.size(); i++) {
                    segments.get(i).close();
                    Files.deleteIfExists(segments.get(i).file());
                }
                segments.subList(cut, segments.size()).clear();
            } else {
                LogSegment tail = segments.get(segments.size() - 1);
                tail.truncateTo(index);
            }
            saveBaseIndex();
        } catch (IOException e) {
            throw new IllegalStateException("raft log truncate failed", e);
        }
    }

    @Override
    public void installSnapshot(long lastIncludedIndex) {
        if (lastIncludedIndex < firstIndex() - 1) {
            return;
        }
        // 保留 (lastIncludedIndex, last] 后缀，删除前缀与旧段
        List<LogEntry> remaining = entriesFrom(lastIncludedIndex + 1);
        try {
            for (LogSegment segment : segments) {
                segment.close();
                Files.deleteIfExists(segment.file());
            }
            segments.clear();
            baseIndex = lastIncludedIndex + 1;
            saveBaseIndex();
        } catch (IOException e) {
            throw new IllegalStateException("raft log snapshot compact failed", e);
        }
        for (LogEntry entry : remaining) {
            append(entry);
        }
    }

    @Override
    public void sync() {
        forceAll();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (flusher != null) {
            flusher.shutdownNow();
        }
        forceAll();
        for (LogSegment segment : segments) {
            try {
                segment.close();
            } catch (IOException ignored) {
                // close best-effort
            }
        }
        segments.clear();
    }

    public Path directory() {
        return dir;
    }

    private void rotate(long firstIndex) throws IOException {
        Path file = dir.resolve(String.format("%s%020d%s",
                SEGMENT_PREFIX, firstIndex, SEGMENT_SUFFIX));
        segments.add(LogSegment.create(file, firstIndex));
    }

    private LogSegment findSegment(long index) {
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (segments.get(i).contains(index)) {
                return segments.get(i);
            }
        }
        return null;
    }

    private void forceAll() {
        if (closed) {
            return;
        }
        for (LogSegment segment : segments) {
            try {
                segment.force();
            } catch (IOException ignored) {
                // force best-effort
            }
        }
    }

    private long loadBaseIndex() throws IOException {
        Path file = dir.resolve(BASE_FILE);
        if (!Files.exists(file)) {
            return 0;
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 16) {
            return 0;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int magic = buffer.getInt();
        if (magic != BASE_MAGIC) {
            return 0;
        }
        long index = buffer.getLong();
        int expectedCrc = buffer.getInt();
        CRC32C crc = new CRC32C();
        crc.update(bytes, 4, 8);
        return crc.getValue() == (expectedCrc & 0xffffffffL) ? index : 0;
    }

    private void saveBaseIndex() throws IOException {
        Path file = dir.resolve(BASE_FILE);
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(BASE_MAGIC);
        buffer.putLong(baseIndex);
        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 4, 8);
        buffer.putInt((int) crc.getValue());
        Files.write(file, buffer.array());
    }

    private void checkWritable() {
        if (closed) {
            throw new IllegalStateException("raft log closed");
        }
    }
}
