package io.tieringkv.storage.wal;

import java.nio.file.Path;

/** WAL 配置（ADR-0014/0015）。 */
public record WALConfig(Path directory, long maxSegmentBytes, FsyncPolicy fsyncPolicy) {

    public WALConfig {
        if (maxSegmentBytes <= 0) {
            throw new IllegalArgumentException("maxSegmentBytes must be positive");
        }
    }

    public static WALConfig defaults(Path directory) {
        return new WALConfig(directory, 64L * 1024 * 1024, FsyncPolicy.EVERY_SEC);
    }

    public enum FsyncPolicy {
        /** 每次 append 后立即 flush + force（严格持久化）。 */
        ALWAYS,
        /** ≤1s 批量 flush + force（Redis everysec 语义，默认）。 */
        EVERY_SEC,
        /** 仅写 OS 缓冲（测试/性能对比）。 */
        NO
    }
}
