package io.tieringkv.storage.wal;

import io.tieringkv.storage.memory.MemTable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 崩溃恢复引擎（ADR-0016 + v6.5.0 预分配零尾兼容）：
 * 按 segment 序号扫描 → 校验 → 重放 → 按需截断损坏尾部。
 * 中段损坏时停止后续重放；PUT 按绝对过期点判定（宕机期间过期的键不复活）；
 * 预分配 / O_DIRECT 段的干净零填充尾区自动跳过且不破坏预分配物理块。
 */
public final class RecoveryManager {

    private final WALConfig config;

    public RecoveryManager(WALConfig config) {
        this.config = config;
    }

    public RecoveryStats recover(MemTable memTable) throws IOException {
        return recoverFrom(memTable, 0, 0);
    }

    public RecoveryStats recoverFrom(
            MemTable memTable, long startSequence, long startOffset) throws IOException {
        return recoverDirectory(config.directory(), memTable, startSequence, startOffset);
    }

    static RecoveryStats recoverDirectory(
            Path directory, MemTable memTable, long startSequence, long startOffset) throws IOException {
        if (!Files.exists(directory)) {
            return new RecoveryStats(0, 0, 0, 0, null);
        }
        long scanned = 0;
        long applied = 0;
        long segmentsReplayed = 0;
        long corruptedDiscarded = 0;
        Path lastGoodPath = null;

        for (long sequence : listSegments(directory)) {
            if (sequence < startSequence) {
                continue;
            }
            Path path = directory.resolve(String.format("%06d.log", sequence));
            long skip = sequence == startSequence ? startOffset : 0;
            try (WALReader reader = new WALReader(path, skip)) {
                segmentsReplayed++;
                while (true) {
                    WALEntry entry;
                    try {
                        entry = reader.next();
                    } catch (WalCorruptionException e) {
                        corruptedDiscarded++;
                        truncateTail(path, reader.offset());
                        break;
                    }
                    if (entry == null) {
                        // 命中预分配 / O_DIRECT 全 0 尾区时为干净逻辑 EOF，保留预分配块不截断；
                        // 其余情况（非零残尾）按需回退到最后有效偏移
                        if (!reader.reachedZeroTail()) {
                            truncateTail(path, reader.offset());
                        }
                        break;
                    }
                    scanned++;
                    apply(entry, memTable);
                    applied++;
                }
                lastGoodPath = path;
            }
        }
        return new RecoveryStats(scanned, applied, segmentsReplayed, corruptedDiscarded, lastGoodPath);
    }

    static void apply(WALEntry entry, MemTable memTable) {
        long now = System.currentTimeMillis();
        if (entry.operation() == WALEntry.Operation.PUT) {
            if (entry.ttlMillis() > 0) {
                long expireAt = entry.timestamp() + entry.ttlMillis();
                long remaining = expireAt - now;
                if (remaining <= 0) {
                    // 宕机期间已过期：显式写 tombstone 避免冷层或检查点旧值复活
                    memTable.putTombstone(entry.key());
                    return;
                }
                memTable.put(entry.key(), entry.value(), remaining);
            } else {
                memTable.put(entry.key(), entry.value());
            }
        } else {
            memTable.putTombstone(entry.key());
        }
    }

    static void truncateTail(Path path, long lastGoodOffset) throws IOException {
        long size;
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.READ)) {
            size = channel.size();
        }
        if (lastGoodOffset >= size) {
            // 干净尾部：无需写打开——只读文件系统（ADR-0342 演练发现）
            // 下恢复仍可完成。
            return;
        }
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.WRITE)) {
            channel.truncate(lastGoodOffset);
        }
    }

    static List<Long> listSegments(Path directory) throws IOException {
        List<Long> sequences = new ArrayList<>();
        if (!Files.exists(directory)) {
            return sequences;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.log")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                sequences.add(Long.parseLong(name.substring(0, name.length() - 4)));
            }
        }
        sequences.sort(Comparator.naturalOrder());
        return sequences;
    }

    public record RecoveryStats(
            long recordsScanned,
            long recordsApplied,
            long segmentsReplayed,
            long corruptedRecordsDiscarded,
            Path lastGoodPath) {
    }
}
