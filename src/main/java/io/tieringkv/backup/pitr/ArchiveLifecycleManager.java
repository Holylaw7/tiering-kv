package io.tieringkv.backup.pitr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 归档生命周期（ADR-0111）：按策略清理段，保护 checkpoint 水位。 */
public final class ArchiveLifecycleManager {

    private final Path archiveDir;
    private final RetentionPolicy policy;

    public ArchiveLifecycleManager(Path archiveDir,
                                   RetentionPolicy policy) {
        this.archiveDir = archiveDir;
        this.policy = policy;
    }

    public List<String> cleanup() throws IOException {
        List<String> removed = new ArrayList<>();
        if (!Files.exists(archiveDir)) {
            return removed;
        }
        List<SegmentInfo> segments = new ArrayList<>();
        try (var stream = Files.list(archiveDir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (!name.startsWith("pitr-")
                        || !name.endsWith(".log")) {
                    continue;
                }
                int index = Integer.parseInt(name.substring(5,
                        name.length() - 4));
                List<PitrRecord> records = PitrWriteLog.read(path);
                long minSeq = records.isEmpty() ? Long.MAX_VALUE
                        : records.get(0).seq();
                segments.add(new SegmentInfo(index, minSeq,
                        Files.getLastModifiedTime(path).toMillis()));
            }
        }
        segments.sort(Comparator.comparingInt(SegmentInfo::index));
        for (int i = 0; i < segments.size(); i++) {
            SegmentInfo segment = segments.get(i);
            long ageMillis = System.currentTimeMillis()
                    - segment.modifiedMillis();
            boolean retain = policy.shouldRetain(segment.index(),
                    ageMillis, segment.minSeq());
            if (!retain) {
                Files.deleteIfExists(archiveDir.resolve(
                        segmentPath(segment.index())));
                removed.add(segmentPath(segment.index()));
            }
        }
        return removed;
    }

    public int segmentCount() throws IOException {
        if (!Files.exists(archiveDir)) {
            return 0;
        }
        try (var stream = Files.list(archiveDir)) {
            return (int) stream.filter(path -> path.getFileName()
                            .toString().startsWith("pitr-"))
                    .count();
        }
    }

    private static String segmentPath(int index) {
        return String.format("pitr-%06d.log", index);
    }

    private record SegmentInfo(int index, long minSeq,
                               long modifiedMillis) {
    }
}
