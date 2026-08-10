package io.tieringkv.storage.cache;

import io.tieringkv.storage.memory.KeyValueEntry;

/**
 * 冷迁移回调（Phase 3 只提供接口，无磁盘实现）。
 * Phase 4/6 将接入 WAL / Bitcask / LSM：先迁移、后删除内存副本。
 */
@FunctionalInterface
public interface MigrationCallback {

    void migrate(KeyValueEntry entry);
}
