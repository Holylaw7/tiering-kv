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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 崩溃恢复引擎（ADR-0016 + v6.5.0 预分配零尾兼容 + Wave 3C K1 两阶段 CLEAR_ALL 屏障恢复）：
 * <ul>
 *   <li>Phase 1：按 segment 序号扫描、校验 CRC32C、截断损坏/半写残尾并收集所有合法 {@code CLEAR_ALL} 位点；</li>
 *   <li>Phase 2：若存在已提交 {@code CLEAR_ALL} 屏障，先全局清空 {@link MemTable}（作废旧 Checkpoint），
 *       再从屏障精确偏移之后重放，并显式忽略任何未提交的高纪元 {@code CLEAR_ALL}。</li>
 * </ul>
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
        BarrierScanResult scan = scanDirectoryBarriers(directory, startSequence, startOffset);
        long committedEpoch = scan.maxClearEpoch();
        if (committedEpoch > 0L) {
            memTable.clear();
            WALManager.WALPosition resume = scan.clearEndPositions().get(committedEpoch);
            ReplayResult replay = replayDirectoryIgnoringClears(
                    directory, memTable, resume.sequence(), resume.offset());
            return new RecoveryStats(
                    scan.recordsScanned(),
                    replay.recordsApplied(),
                    scan.segmentsReplayed(),
                    scan.corruptedDiscarded(),
                    scan.lastGoodPath(),
                    committedEpoch,
                    committedEpoch);
        }
        ReplayResult replay = replayDirectoryIgnoringClears(
                directory, memTable, startSequence, startOffset);
        return new RecoveryStats(
                replay.recordsScanned(),
                replay.recordsApplied(),
                replay.segmentsReplayed(),
                scan.corruptedDiscarded(),
                scan.lastGoodPath(),
                0L,
                0L);
    }

    /**
     * Phase 1：扫描单目录自 {@code (startSequence, startOffset)} 起的全部段，校验 CRC32C、截断损坏/半写残尾，
     * 并收集全部合法 {@code CLEAR_ALL} 纪元及其精确结束位点（下一条记录起始偏移）。
     */
    static BarrierScanResult scanDirectoryBarriers(
            Path directory, long startSequence, long startOffset) throws IOException {
        if (!Files.exists(directory)) {
            return new BarrierScanResult(0L, Map.of(), 0L, 0L, 0L, null);
        }
        long maxClearEpoch = 0L;
        Map<Long, WALManager.WALPosition> clearEndPositions = new LinkedHashMap<>();
        long scanned = 0L;
        long segmentsReplayed = 0L;
        long corruptedDiscarded = 0L;
        Path lastGoodPath = null;

        for (long sequence : listSegments(directory)) {
            if (sequence < startSequence) {
                continue;
            }
            Path path = directory.resolve(String.format("%06d.log", sequence));
            long skip = sequence == startSequence ? startOffset : 0L;
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
                    if (entry.operation() == WALEntry.Operation.CLEAR_ALL) {
                        long epoch = entry.version();
                        if (epoch > maxClearEpoch) {
                            maxClearEpoch = epoch;
                        }
                        clearEndPositions.put(epoch, new WALManager.WALPosition(sequence, reader.offset()));
                    }
                }
                lastGoodPath = path;
            }
        }
        return new BarrierScanResult(
                maxClearEpoch, clearEndPositions, scanned, segmentsReplayed, corruptedDiscarded, lastGoodPath);
    }

    /**
     * Phase 2：自已确定起点重放记录，显式忽略任意 {@code CLEAR_ALL} 记录
     * （此时出现的任何 {@code CLEAR_ALL} 必为未提交的跨分片撕裂屏障，绝不误清数据）。
     */
    static ReplayResult replayDirectoryIgnoringClears(
            Path directory, MemTable memTable, long startSequence, long startOffset) throws IOException {
        if (!Files.exists(directory)) {
            return new ReplayResult(0L, 0L, 0L, null);
        }
        long scanned = 0L;
        long applied = 0L;
        long segmentsReplayed = 0L;
        Path lastGoodPath = null;

        for (long sequence : listSegments(directory)) {
            if (sequence < startSequence) {
                continue;
            }
            Path path = directory.resolve(String.format("%06d.log", sequence));
            long skip = sequence == startSequence ? startOffset : 0L;
            try (WALReader reader = new WALReader(path, skip)) {
                segmentsReplayed++;
                while (true) {
                    WALEntry entry;
                    try {
                        entry = reader.next();
                    } catch (WalCorruptionException e) {
                        break;
                    }
                    if (entry == null) {
                        break;
                    }
                    scanned++;
                    if (entry.operation() == WALEntry.Operation.CLEAR_ALL) {
                        continue;
                    }
                    apply(entry, memTable);
                    applied++;
                }
                lastGoodPath = path;
            }
        }
        return new ReplayResult(scanned, applied, segmentsReplayed, lastGoodPath);
    }

    static void apply(WALEntry entry, MemTable memTable) {
        long now = System.currentTimeMillis();
        switch (entry.operation()) {
            case PUT -> {
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
            }
            case DELETE -> memTable.putTombstone(entry.key());
            case CLEAR_ALL -> {
                // Phase 2 中由上层屏障逻辑统一处理，单条 apply 显式忽略，绝不误写空键墓碑
            }
        }
    }

    record BarrierScanResult(
            long maxClearEpoch,
            Map<Long, WALManager.WALPosition> clearEndPositions,
            long recordsScanned,
            long segmentsReplayed,
            long corruptedDiscarded,
            Path lastGoodPath) {
    }

    record ReplayResult(
            long recordsScanned,
            long recordsApplied,
            long segmentsReplayed,
            Path lastGoodPath) {
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
            Path lastGoodPath,
            long committedClearEpoch,
            long maxObservedClearEpoch) {

        /** 兼容既有 5 参构造。 */
        public RecoveryStats(
                long recordsScanned,
                long recordsApplied,
                long segmentsReplayed,
                long corruptedRecordsDiscarded,
                Path lastGoodPath) {
            this(recordsScanned, recordsApplied, segmentsReplayed,
                    corruptedRecordsDiscarded, lastGoodPath, 0L, 0L);
        }

        /** 本次恢复是否识别并应用了跨全部分片持久提交的 CLEAR_ALL 屏障。 */
        public boolean clearedByBarrier() {
            return committedClearEpoch > 0L;
        }
    }
}
