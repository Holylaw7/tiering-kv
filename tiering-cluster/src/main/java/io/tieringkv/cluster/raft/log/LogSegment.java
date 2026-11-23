package io.tieringkv.cluster.raft.log;

import io.tieringkv.cluster.raft.LogEntry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * RaftLog 文件段（ADR-0039）：一段连续日志，持有 index → 文件偏移索引。
 * 文件为追加式，段只允许尾部追加与整体截断。
 */
public final class LogSegment implements AutoCloseable {

    private final Path file;
    private final FileChannel channel;
    private final List<Long> offsets = new ArrayList<>();
    private long firstIndex;
    private long lastIndex = -1;
    private long sizeBytes;

    private LogSegment(Path file, FileChannel channel) {
        this.file = file;
        this.channel = channel;
    }

    /** 新建空段，段内首条日志索引为 firstIndex。 */
    public static LogSegment create(Path file, long firstIndex) throws IOException {
        FileChannel channel = FileChannel.open(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        LogSegment segment = new LogSegment(file, channel);
        segment.firstIndex = firstIndex;
        return segment;
    }

    /** 打开已有段并扫描构建索引；返回段与恢复统计。 */
    public static Scan open(Path file) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
        LogSegment segment = new LogSegment(file, channel);
        return segment.scan();
    }

    private Scan scan() throws IOException {
        long offset = 0;
        long validBytes = 0;
        long originalBytes = channel.size();
        boolean first = true;
        while (offset + 30 <= channel.size()) {
            long recordStart = offset;
            try {
                ByteBuffer record = RaftLogReader.readRecord(channel, offset);
                LogEntry entry = LogEntryCodec.decode(record.duplicate()).entry();
                if (first) {
                    firstIndex = entry.index();
                    first = false;
                }
                offsets.add(recordStart);
                lastIndex = entry.index();
                offset += record.remaining();
                validBytes = offset;
            } catch (LogEntryCodec.CorruptionException | IOException e) {
                break;
            }
        }
        sizeBytes = validBytes;
        if (validBytes < channel.size()) {
            channel.truncate(validBytes);
        }
        return new Scan(this, validBytes, originalBytes);
    }

    public void append(LogEntry entry) throws IOException {
        long start = channel.size();
        ByteBuffer record = RaftLogWriter.writeEntry(channel, entry);
        if (offsets.isEmpty()) {
            firstIndex = entry.index();
        }
        offsets.add(start);
        lastIndex = entry.index();
        sizeBytes = channel.size();
    }

    public LogEntry entryAt(long index) throws IOException {
        int slot = (int) (index - firstIndex);
        if (slot < 0 || slot >= offsets.size()) {
            throw new IllegalArgumentException("index out of segment: " + index);
        }
        ByteBuffer record = RaftLogReader.readRecord(channel, offsets.get(slot));
        return LogEntryCodec.decode(record).entry();
    }

    /** 保留 [firstIndex, index) 的条目，物理截断。 */
    public void truncateTo(long index) throws IOException {
        if (index <= firstIndex) {
            clear();
            return;
        }
        if (index >= lastIndex + 1) {
            return;
        }
        int keep = (int) (index - firstIndex);
        long cutOffset = offsets.get(keep);
        channel.truncate(cutOffset);
        while (offsets.size() > keep) {
            offsets.remove(offsets.size() - 1);
        }
        lastIndex = index - 1;
        sizeBytes = cutOffset;
    }

    public void clear() throws IOException {
        channel.truncate(0);
        offsets.clear();
        lastIndex = -1;
        sizeBytes = 0;
    }

    public boolean contains(long index) {
        return !offsets.isEmpty() && index >= firstIndex && index <= lastIndex;
    }

    public long firstIndex() {
        return firstIndex;
    }

    public long lastIndex() {
        return lastIndex;
    }

    public int size() {
        return offsets.size();
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public Path file() {
        return file;
    }

    public void force() throws IOException {
        channel.force(false);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    public record Scan(LogSegment segment, long validBytes, long originalBytes) {
    }
}
