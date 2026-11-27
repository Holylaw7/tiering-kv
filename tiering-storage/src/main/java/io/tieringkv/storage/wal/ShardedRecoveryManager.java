package io.tieringkv.storage.wal;

import io.tieringkv.storage.memory.MemTable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 分片 WAL 并行恢复与平滑迁移引擎（v6.5.0 Phase 1）：
 * <ol>
 *   <li>单分片或无 {@code shard-*} 目录时直接走 {@link RecoveryManager}（100% 兼容旧版）；</li>
 *   <li>检测到旧版根目录 {@code *.log} 而新配置启用了多分片时，自动无损恢复旧日志并平滑归档迁移；</li>
 *   <li>正常多分片恢复时，由于跨分片 Key 集合严格互斥，多线程按分片全并行解码并直接写入 {@link MemTable}；</li>
 *   <li>若检测到配置变更了 {@code wal.shards}（{@code META.shardCount != config.shardCount}），
 *       按 {@code (timestamp, version)} 归并重放全量旧分片并备份归档旧目录，杜绝重分片丢数据。</li>
 * </ol>
 */
public final class ShardedRecoveryManager {

    static final String META_FILE = "META";
    private static final int FORMAT_VERSION_SHARDED = 2;

    private final WALConfig config;

    public ShardedRecoveryManager(WALConfig config) {
        this.config = config;
    }

    public RecoveryManager.RecoveryStats recover(MemTable memTable) throws IOException {
        return recoverFrom(memTable, null);
    }

    public RecoveryManager.RecoveryStats recoverFrom(
            MemTable memTable, CheckpointManager.Checkpoint checkpoint) throws IOException {
        Path root = config.directory();
        if (!Files.exists(root)) {
            return new RecoveryManager.RecoveryStats(0, 0, 0, 0, null);
        }

        List<Path> shardDirs = listShardDirectories(root);
        List<Long> legacyRootLogs = RecoveryManager.listSegments(root);
        int metaShards = readMetaShardCount(root);

        // 场景 1：仅存在传统单目录日志（v6.4.0 及更早版本，或 shardCount==1）
        if (shardDirs.isEmpty()) {
            long startSeq = (checkpoint != null && !checkpoint.isSharded()) ? checkpoint.segmentSequence() : 0L;
            long startOff = (checkpoint != null && !checkpoint.isSharded()) ? checkpoint.offset() : 0L;
            RecoveryManager.RecoveryStats stats = RecoveryManager.recoverDirectory(
                    root, memTable, startSeq, startOff);
            if (config.shardCount() > 1 && !legacyRootLogs.isEmpty()) {
                archiveLegacyRootLogs(root, legacyRootLogs);
                writeMeta(root, config.shardCount());
            }
            return stats;
        }

        int existingShards = metaShards > 0 ? metaShards : shardDirs.size();

        // 场景 2：分片数一致 → 按分片全并行恢复（跨分片 Key 严格不相交）
        if (existingShards == config.shardCount()) {
            List<WALManager.WALPosition> startPositions = resolveStartPositions(checkpoint, existingShards);
            return recoverShardsParallel(root, existingShards, startPositions, memTable);
        }

        // 场景 3：运维变更了 wal.shards（例如 16→8 或 8→16）→ 跨分片安全归并重放 + 归档旧分片目录
        List<WALManager.WALPosition> startPositions = resolveStartPositions(checkpoint, existingShards);
        RecoveryManager.RecoveryStats stats = recoverResharded(shardDirs, startPositions, memTable);
        archiveOldShardDirs(root, shardDirs);
        if (config.shardCount() > 1) {
            writeMeta(root, config.shardCount());
        } else {
            Files.deleteIfExists(root.resolve(META_FILE));
        }
        return stats;
    }

    private RecoveryManager.RecoveryStats recoverShardsParallel(
            Path root,
            int shardCount,
            List<WALManager.WALPosition> startPositions,
            MemTable memTable) throws IOException {
        int threads = Math.min(shardCount, Math.max(1, Runtime.getRuntime().availableProcessors()));
        if (threads <= 1) {
            long scanned = 0, applied = 0, segments = 0, corrupted = 0;
            Path lastGood = null;
            for (int i = 0; i < shardCount; i++) {
                Path dir = root.resolve(String.format(Locale.ROOT, "shard-%02d", i));
                WALManager.WALPosition pos = startPositions.get(i);
                RecoveryManager.RecoveryStats s = RecoveryManager.recoverDirectory(
                        dir, memTable, pos.sequence(), pos.offset());
                scanned += s.recordsScanned();
                applied += s.recordsApplied();
                segments += s.segmentsReplayed();
                corrupted += s.corruptedRecordsDiscarded();
                if (s.lastGoodPath() != null) {
                    lastGood = s.lastGoodPath();
                }
            }
            return new RecoveryManager.RecoveryStats(scanned, applied, segments, corrupted, lastGood);
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "wal-shard-recover");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<RecoveryManager.RecoveryStats>> futures = new ArrayList<>(shardCount);
            for (int i = 0; i < shardCount; i++) {
                final int shardId = i;
                Path dir = root.resolve(String.format(Locale.ROOT, "shard-%02d", shardId));
                WALManager.WALPosition pos = startPositions.get(shardId);
                futures.add(pool.submit(() -> RecoveryManager.recoverDirectory(
                        dir, memTable, pos.sequence(), pos.offset())));
            }
            long scanned = 0, applied = 0, segments = 0, corrupted = 0;
            Path lastGood = null;
            for (Future<RecoveryManager.RecoveryStats> future : futures) {
                RecoveryManager.RecoveryStats s = future.get();
                scanned += s.recordsScanned();
                applied += s.recordsApplied();
                segments += s.segmentsReplayed();
                corrupted += s.corruptedRecordsDiscarded();
                if (s.lastGoodPath() != null) {
                    lastGood = s.lastGoodPath();
                }
            }
            return new RecoveryManager.RecoveryStats(scanned, applied, segments, corrupted, lastGood);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("sharded WAL recovery interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException("sharded WAL recovery failed", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** 重分片归并恢复：对可能重叠的旧分片按 (timestamp, version) 选出每个 Key 的最新状态。 */
    private RecoveryManager.RecoveryStats recoverResharded(
            List<Path> shardDirs,
            List<WALManager.WALPosition> startPositions,
            MemTable memTable) throws IOException {
        Map<ByteBuffer, WALEntry> merged = new HashMap<>();
        long scanned = 0;
        long segments = 0;
        long corrupted = 0;
        Path lastGood = null;

        for (int i = 0; i < shardDirs.size(); i++) {
            Path dir = shardDirs.get(i);
            WALManager.WALPosition start = i < startPositions.size()
                    ? startPositions.get(i)
                    : new WALManager.WALPosition(0, 0);
            for (long seq : RecoveryManager.listSegments(dir)) {
                if (seq < start.sequence()) {
                    continue;
                }
                Path path = dir.resolve(String.format(Locale.ROOT, "%06d.log", seq));
                long skip = seq == start.sequence() ? start.offset() : 0L;
                try (WALReader reader = new WALReader(path, skip)) {
                    segments++;
                    while (true) {
                        WALEntry entry;
                        try {
                            entry = reader.next();
                        } catch (WalCorruptionException e) {
                            corrupted++;
                            RecoveryManager.truncateTail(path, reader.offset());
                            break;
                        }
                        if (entry == null) {
                            if (!reader.reachedZeroTail()) {
                                RecoveryManager.truncateTail(path, reader.offset());
                            }
                            break;
                        }
                        scanned++;
                        ByteBuffer keyWrap = ByteBuffer.wrap(entry.key());
                        WALEntry prev = merged.get(keyWrap);
                        if (prev == null || isNewer(entry, prev)) {
                            merged.put(keyWrap, entry);
                        }
                    }
                    lastGood = path;
                }
            }
        }
        long applied = 0;
        for (WALEntry entry : merged.values()) {
            RecoveryManager.apply(entry, memTable);
            applied++;
        }
        return new RecoveryManager.RecoveryStats(scanned, applied, segments, corrupted, lastGood);
    }

    private static boolean isNewer(WALEntry candidate, WALEntry existing) {
        if (candidate.timestamp() != existing.timestamp()) {
            return candidate.timestamp() > existing.timestamp();
        }
        return candidate.version() >= existing.version();
    }

    private static List<WALManager.WALPosition> resolveStartPositions(
            CheckpointManager.Checkpoint checkpoint, int shardCount) {
        List<WALManager.WALPosition> result = new ArrayList<>(shardCount);
        boolean usable = checkpoint != null
                && checkpoint.shardPositions() != null
                && checkpoint.shardPositions().size() == shardCount;
        for (int i = 0; i < shardCount; i++) {
            result.add(usable ? checkpoint.shardPositions().get(i) : new WALManager.WALPosition(0, 0));
        }
        return result;
    }

    static List<Path> listShardDirectories(Path root) throws IOException {
        List<Path> dirs = new ArrayList<>();
        if (!Files.exists(root)) {
            return dirs;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "shard-*")) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    dirs.add(path);
                }
            }
        }
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return dirs;
    }

    static int readMetaShardCount(Path root) {
        Path meta = root.resolve(META_FILE);
        if (!Files.exists(meta)) {
            return -1;
        }
        try {
            for (String line : Files.readAllLines(meta, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("shardCount=")) {
                    return Integer.parseInt(trimmed.substring("shardCount=".length()).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    static void writeMeta(Path root, int shardCount) throws IOException {
        Files.createDirectories(root);
        Path tmp = root.resolve(META_FILE + ".tmp");
        Path target = root.resolve(META_FILE);
        String body = "formatVersion=" + FORMAT_VERSION_SHARDED + "\nshardCount=" + shardCount + "\n";
        Files.writeString(tmp, body, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void archiveLegacyRootLogs(Path root, List<Long> legacySeqs) throws IOException {
        Path backupDir = root.resolve("legacy-migrated-" + System.currentTimeMillis());
        Files.createDirectories(backupDir);
        for (long seq : legacySeqs) {
            String name = String.format(Locale.ROOT, "%06d.log", seq);
            Path src = root.resolve(name);
            if (Files.exists(src)) {
                Files.move(src, backupDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void archiveOldShardDirs(Path root, List<Path> oldDirs) throws IOException {
        Path backupDir = root.resolve("reshard-backup-" + System.currentTimeMillis());
        Files.createDirectories(backupDir);
        for (Path dir : oldDirs) {
            Files.move(dir, backupDir.resolve(dir.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
