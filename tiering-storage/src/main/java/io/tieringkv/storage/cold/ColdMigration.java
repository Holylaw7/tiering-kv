package io.tieringkv.storage.cold;

import io.tieringkv.storage.cache.MigrationResult;
import io.tieringkv.storage.cache.TierMigration;
import io.tieringkv.storage.memory.KeyValueEntry;

/** 冷迁移实现（ADR-0017）：写入冷层 pending；失败返回 FAILED（内存保留）。 */
public final class ColdMigration implements TierMigration {

    private final ColdStorageEngine cold;

    public ColdMigration(ColdStorageEngine cold) {
        this.cold = cold;
    }

    @Override
    public MigrationResult migrate(KeyValueEntry entry) {
        try {
            cold.put(entry);
            return MigrationResult.SUCCESS;
        } catch (RuntimeException e) {
            return MigrationResult.FAILED;
        }
    }
}
