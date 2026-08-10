package io.tieringkv.storage.wal;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** WAL 段管理（ADR-0014）：wal/%06d.log 命名、按序号升序枚举、轮转。 */
public final class SegmentManager implements AutoCloseable {

    private final Path directory;
    private LogSegment current;
    private long nextSequence;

    public SegmentManager(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        List<Long> existing = listSegments();
        long start = existing.isEmpty() ? 1 : existing.get(existing.size() - 1);
        this.current = LogSegment.openOrCreate(directory, start);
        this.nextSequence = start + 1;
    }

    public LogSegment current() {
        return current;
    }

    /** 强制关闭当前段并创建下一序号段。 */
    public void rotate() throws IOException {
        current.force();
        current.close();
        current = LogSegment.openOrCreate(directory, nextSequence++);
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

    @Override
    public void close() throws IOException {
        if (current != null) {
            current.close();
            current = null;
        }
    }
}
