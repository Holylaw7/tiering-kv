package io.tieringkv.storage.cache;

import io.tieringkv.storage.memory.KeyValueEntry;

/**
 * 冷迁移接口（ADR-0013）：先迁移、后删除。
 *
 * <p>Phase 3 为占位实现（{@link #discard()}）；Phase 4/6 接入 WAL / Bitcask /
 * LSM 时实现真实迁移，并利用结果码处理磁盘满、IO 错误等失败场景。
 */
@FunctionalInterface
public interface TierMigration {

    MigrationResult migrate(KeyValueEntry entry);

    /** Phase 3 占位：无盘迁移视为成功（数据被丢弃并释放内存）。 */
    static TierMigration discard() {
        return entry -> MigrationResult.SUCCESS;
    }
}
