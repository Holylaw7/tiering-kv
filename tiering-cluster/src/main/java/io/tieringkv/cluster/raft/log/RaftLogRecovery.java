package io.tieringkv.cluster.raft.log;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * RaftLog 恢复（ADR-0039）：扫描段 → 校验 CRC → 截断损坏尾部 →
 * 删除损坏段之后的全部段（日志连续性依赖）。
 */
public final class RaftLogRecovery {

    private RaftLogRecovery() {
    }

    public static Result recover(Path dir) throws IOException {
        Files.createDirectories(dir);
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "segment-*.log")) {
            for (Path file : stream) {
                files.add(file);
            }
        }
        files.sort(Comparator.comparing(RaftLogRecovery::segmentFirstIndex));

        List<LogSegment> segments = new ArrayList<>();
        long truncatedBytes = 0;
        int recoveredEntries = 0;
        long expectedNextIndex = -1;
        boolean gap = false;
        for (Path file : files) {
            if (gap) {
                Files.deleteIfExists(file);
                continue;
            }
            LogSegment.Scan scan = LogSegment.open(file);
            LogSegment segment = scan.segment();
            if (segment.size() == 0) {
                // 空/损坏段：其后所有段依赖其连续性，视为不可信
                segment.close();
                Files.deleteIfExists(file);
                gap = true;
                continue;
            }
            if (expectedNextIndex >= 0 && segment.firstIndex() != expectedNextIndex) {
                // 段间不连续：删除后续段，保持日志线性
                segment.close();
                Files.deleteIfExists(file);
                gap = true;
                continue;
            }
            truncatedBytes += (scan.originalBytes() - scan.validBytes());
            recoveredEntries += segment.size();
            expectedNextIndex = segment.lastIndex() + 1;
            segments.add(segment);
        }
        return new Result(segments, truncatedBytes, recoveredEntries);
    }

    private static long segmentFirstIndex(Path file) {
        String name = file.getFileName().toString();
        String number = name.substring("segment-".length(), name.length() - ".log".length());
        return Long.parseLong(number);
    }

    public record Result(List<LogSegment> segments, long truncatedBytes, int recoveredEntries) {
    }
}
