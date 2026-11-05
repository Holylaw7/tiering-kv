package io.tieringkv.webconsole.web.dto;

/**
 * 实时指标（由一次 INFO 现场解析，不落库）。
 *
 * <p>TD-095：新增存储分层字段，来源是内核 `# Tiering` 段落
 * （MemTable / SSTable / Flush / 迁移 / BlockCache），全部为真实计数，
 * 内核不提供时保持 0，不做估算。
 */
public record LiveMetrics(
        String version,
        long uptimeSeconds,
        long usedMemory,
        long maxMemory,
        long usedMemoryJvm,
        long totalKeys,
        int connections,
        int activeRequests,
        double qps,
        double opsPerSec,
        double avgLatencyMs,
        long errors,
        long maxJvmHeap,
        /* ---- 存储分层 ---- */
        long memtableUsedBytes,
        long memtableMaxBytes,
        long memtableEntries,
        long coldSstableCount,
        long coldDiskUsageBytes,
        double coldShare,
        long flushCount,
        long flushBytes,
        long migrationPending,
        long migrationSuccess,
        long migrationFailed,
        long cacheHits,
        long cacheMisses,
        double cacheHitRate,
        long cacheEvictions) {
}
