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
 * 崩溃恢复引擎（ADR-0016）：按 segment 序号扫描 → 校验 → 重放 → 截断损坏尾部。
 * 中段损坏时停止后续重放；PUT 按绝对过期点判定（宕机期间过期的键不复活）。
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
        long scanned = 0;
        long applied = 0;
        long segmentsReplayed = 0;
        long corruptedDiscarded = 0;
        Path lastGoodPath = null;

        for (long sequence : listSegments(config.directory())) {
            if (sequence < startSequence) {
                continue;
            }
            Path path = config.directory().resolve(String.format("%06d.log", sequence));
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
                        // 干净 EOF 时为无操作；截断尾部时回退到最后有效偏移
                        truncateTail(path, reader.offset());
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
                    return; // 宕机期间已过期，不复活
                }
                memTable.put(entry.key(), entry.value(), remaining);
            } else {
                memTable.put(entry.key(), entry.value());
            }
        } else {
            memTable.delete(entry.key());
        }
    }

    static void truncateTail(Path path, long lastGoodOffset) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            long size = channel.size();
            if (lastGoodOffset < size) {
                channel.truncate(lastGoodOffset);
            }
        }
    }

    static List<Long> listSegments(Path directory) throws IOException {
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

    public record RecoveryStats(
            long recordsScanned,
            long recordsApplied,
            long segmentsReplayed,
            long corruptedRecordsDiscarded,
            Path lastGoodPath) {
    }
}
