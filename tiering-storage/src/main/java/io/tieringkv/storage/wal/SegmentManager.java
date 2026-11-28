package io.tieringkv.storage.wal;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * WAL 段管理（ADR-0014 + v6.5.0 预分配与 O_DIRECT 支持）：
 * 负责单目录内 {@code %06d.log} 命名、升序枚举、轮转与关闭。
 */
public final class SegmentManager implements AutoCloseable {

    private static final long MAX_PREALLOC_BYTES = 64L * 1024 * 1024;

    private final Path directory;
    private final WALConfig.IoMode ioMode;
    private final long preallocateBytes;
    private LogSegment current;
    private long nextSequence;

    public SegmentManager(Path directory) throws IOException {
        this(directory, WALConfig.IoMode.NIO, 0L);
    }

    public SegmentManager(WALConfig shardConfig) throws IOException {
        this(shardConfig.directory(),
                shardConfig.ioMode(),
                shardConfig.preallocateSegments()
                        ? Math.min(shardConfig.maxSegmentBytes(), MAX_PREALLOC_BYTES)
                        : 0L);
    }

    public SegmentManager(Path directory, WALConfig.IoMode ioMode, long preallocateBytes) throws IOException {
        this.directory = directory;
        this.ioMode = ioMode == null ? WALConfig.IoMode.NIO : ioMode;
        this.preallocateBytes = Math.max(0L, preallocateBytes);
        Files.createDirectories(directory);
        List<Long> existing = listSegments();
        long start = existing.isEmpty() ? 1 : existing.get(existing.size() - 1);
        this.current = LogSegment.openOrCreate(directory, start, this.ioMode, this.preallocateBytes);
        this.nextSequence = start + 1;
    }

    public LogSegment current() {
        return current;
    }

    /** 强制刷盘关闭当前段并创建下一序号段。 */
    public void rotate() throws IOException {
        current.forceMetadata();
        current.close();
        current = LogSegment.openOrCreate(directory, nextSequence++, ioMode, preallocateBytes);
    }

    public List<Long> listSegments() throws IOException {
        List<Long> sequences = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.log")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                sequences.add(Long.parseLong(name.substring(0, name.length() - 4)));
            }
        }
        sequences.sort(Comparator.naturalOrder());
        return sequences;
    }

    public Path segmentPath(long sequence) {
        return directory.resolve(String.format(Locale.ROOT, "%06d.log", sequence));
    }

    /**
     * 安全清理严格早于 {@code keepFromSequence} 的历史封闭段文件（永不删除当前活跃段或目标段本身）。
     * 调用方须持有对应分片的写锁且已确保 {@code keepFromSequence} 上的屏障记录完成 {@code fdatasync}。
     */
    public int deleteSegmentsBefore(long keepFromSequence) throws IOException {
        long activeSeq = current == null ? Long.MAX_VALUE : current.sequence();
        long boundary = Math.min(keepFromSequence, activeSeq);
        int deleted = 0;
        for (long seq : listSegments()) {
            if (seq < boundary) {
                if (Files.deleteIfExists(segmentPath(seq))) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    public String activeIoBackend() {
        return current == null ? "nio" : current.ioBackendName();
    }

    public boolean isPreallocated() {
        return current != null && current.isPreallocated();
    }

    @Override
    public void close() throws IOException {
        if (current != null) {
            current.close();
            current = null;
        }
    }
}
