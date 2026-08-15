package io.tieringkv.storage.wal;

import io.tieringkv.storage.memory.MemTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 并行崩溃恢复（ADR-0329，TD-007）：各 WAL 段并行解析（解码 + CRC
 * 校验），主线程按段序号串行应用——解码/IO 并行、应用保持段序。
 * 中段损坏：截断尾部并停止后续段（与串行语义一致）。
 */
public final class ParallelRecoveryManager {

    private final WALConfig config;
    private final int parallelism;

    public ParallelRecoveryManager(WALConfig config) {
        this(config, Runtime.getRuntime().availableProcessors());
    }

    public ParallelRecoveryManager(WALConfig config, int parallelism) {
        if (config == null || parallelism <= 0) {
            throw new IllegalArgumentException(
                    "config and parallelism >= 1 required");
        }
        this.config = config;
        this.parallelism = parallelism;
    }

    public RecoveryManager.RecoveryStats recover(MemTable memTable)
            throws IOException {
        List<Long> sequences =
                RecoveryManager.listSegments(config.directory());
        if (sequences.size() < 2) {
            // 单段：串行路径（并行无收益）
            return new RecoveryManager(config).recover(memTable);
        }

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(parallelism, sequences.size()));
        Map<Long, Future<SegmentResult>> futures = new HashMap<>();
        try {
            for (Long sequence : sequences) {
                futures.put(sequence,
                        pool.submit(() -> parseSegment(sequence)));
            }
            long scanned = 0;
            long applied = 0;
            long segmentsReplayed = 0;
            long corruptedDiscarded = 0;
            Path lastGoodPath = null;
            for (Long sequence : sequences) {
                SegmentResult result;
                try {
                    result = futures.get(sequence).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "parallel recovery interrupted", e);
                } catch (ExecutionException e) {
                    throw new IOException(
                            "parallel segment parse failed",
                            e.getCause());
                }
                for (WALEntry entry : result.entries()) {
                    scanned++;
                    RecoveryManager.apply(entry, memTable);
                    applied++;
                }
                Path path = pathOf(sequence);
                if (result.corrupted()) {
                    RecoveryManager.truncateTail(path,
                            result.lastGoodOffset());
                    corruptedDiscarded++;
                    break;
                }
                segmentsReplayed++;
                lastGoodPath = path;
            }
            return new RecoveryManager.RecoveryStats(scanned, applied,
                    segmentsReplayed, corruptedDiscarded, lastGoodPath);
        } finally {
            pool.shutdownNow();
        }
    }

    private SegmentResult parseSegment(long sequence)
            throws IOException {
        Path path = pathOf(sequence);
        List<WALEntry> entries = new ArrayList<>();
        try (WALReader reader = new WALReader(path, 0)) {
            while (true) {
                WALEntry entry;
                try {
                    entry = reader.next();
                } catch (WalCorruptionException e) {
                    return new SegmentResult(entries, true,
                            reader.offset());
                }
                if (entry == null) {
                    return new SegmentResult(entries, false,
                            reader.offset());
                }
                entries.add(entry);
            }
        }
    }

    private Path pathOf(long sequence) {
        return config.directory().resolve(
                String.format("%06d.log", sequence));
    }

    private record SegmentResult(List<WALEntry> entries,
                                 boolean corrupted,
                                 long lastGoodOffset) {
    }
}
