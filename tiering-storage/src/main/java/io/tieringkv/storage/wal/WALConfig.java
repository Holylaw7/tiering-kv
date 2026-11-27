package io.tieringkv.storage.wal;

import java.nio.file.Path;
import java.util.Locale;

/**
 * WAL 配置（ADR-0014/0015 + v6.5.0 Sharded WAL / Direct IO / Preallocate 扩展）。
 *
 * <p>保留原有 3 参构造（默认 {@code shardCount=1, ioMode=NIO, preallocateSegments=false}），
 * 确保全部既有单测与旧版单 WAL 目录语义 100% 向后兼容；生产环境可通过配置文件启用
 * 多分片 WAL（如 {@code shardCount=16}）、段预分配与 {@code O_DIRECT} 对齐写。
 */
public record WALConfig(
        Path directory,
        long maxSegmentBytes,
        FsyncPolicy fsyncPolicy,
        int shardCount,
        IoMode ioMode,
        boolean preallocateSegments) {

    public static final int MAX_SHARDS = 64;

    public WALConfig {
        if (directory == null) {
            throw new IllegalArgumentException("directory required");
        }
        if (maxSegmentBytes <= 0) {
            throw new IllegalArgumentException("maxSegmentBytes must be positive");
        }
        if (fsyncPolicy == null) {
            throw new IllegalArgumentException("fsyncPolicy required");
        }
        if (shardCount < 1 || shardCount > MAX_SHARDS || !WalShardRouter.isPowerOfTwo(shardCount)) {
            throw new IllegalArgumentException(
                    "shardCount must be a power of two in [1, " + MAX_SHARDS + "], got: " + shardCount);
        }
        if (ioMode == null) {
            ioMode = IoMode.NIO;
        }
    }

    /** 向后兼容 3 参构造：单 WAL、NIO、不预分配（保留既有测试与旧目录布局）。 */
    public WALConfig(Path directory, long maxSegmentBytes, FsyncPolicy fsyncPolicy) {
        this(directory, maxSegmentBytes, fsyncPolicy, 1, IoMode.NIO, false);
    }

    /** 便捷 4 参构造：指定分片数，默认 NIO。 */
    public WALConfig(Path directory, long maxSegmentBytes, FsyncPolicy fsyncPolicy, int shardCount) {
        this(directory, maxSegmentBytes, fsyncPolicy, shardCount, IoMode.NIO, false);
    }

    public static WALConfig defaults(Path directory) {
        return new WALConfig(directory, 64L * 1024 * 1024, FsyncPolicy.EVERY_SEC, 1, IoMode.NIO, false);
    }

    public static WALConfig shardedDefaults(Path directory, int shardCount) {
        return new WALConfig(directory, 64L * 1024 * 1024, FsyncPolicy.EVERY_SEC,
                shardCount, IoMode.AUTO, true);
    }

    /** 返回单个分片对应的子目录配置；单分片时直接使用根目录（保持旧目录结构）。 */
    public WALConfig forShard(int shardId) {
        if (shardCount == 1) {
            return this;
        }
        Path shardDir = directory.resolve(String.format(Locale.ROOT, "shard-%02d", shardId));
        return new WALConfig(shardDir, maxSegmentBytes, fsyncPolicy, 1, ioMode, preallocateSegments);
    }

    public enum FsyncPolicy {
        /** 每次 append/batch 后立即 fdatasync（严格持久化）。 */
        ALWAYS,
        /** ≤1s 批量 fdatasync（Redis everysec 语义，默认）。 */
        EVERY_SEC,
        /** 仅写 OS 缓冲（测试/性能对比）。 */
        NO
    }

    public enum IoMode {
        /** 自动选择（默认走 FileChannel + 预分配 + fdatasync，兼顾 EVERY_SEC/ALWAYS 吞吐与跨平台稳定）。 */
        AUTO,
        /** 标准 FileChannel 堆外 DirectByteBuffer 直写。 */
        NIO,
        /** 显式启用 Linux ExtendedOpenOption.DIRECT 4KB 对齐直写（极致长尾抖动收敛），不支持时自动回退 NIO。 */
        DIRECT
    }
}
