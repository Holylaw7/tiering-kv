package io.tieringkv.storage.cache;

import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.memory.TimeSource;
import io.tieringkv.storage.wal.WALEntry;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WalWriteException;

/**
 * 内存压力驱动的淘汰管理器（ADR-0012）：
 * usedMemory &gt; maxMemory 时选择候选 → 存活校验 → 迁移回调 → 删除 → EVICT 事件。
 * 每轮有上限，避免 tombstone 边界下死循环；回调/删除顺序保证"先迁移后删除"。
 */
public final class EvictionManager {

    private final MemTable memTable;
    private final MemoryManager memoryManager;
    private final EvictionPolicy policy;
    private final TierMigration migration;
    private final WALManager wal;
    private final TimeSource timeSource;
    private final int maxEvictionsPerCycle;
    private static final int MAX_MIGRATION_ATTEMPTS = 3;

    public EvictionManager(
            MemTable memTable,
            MemoryManager memoryManager,
            EvictionPolicy policy,
            TierMigration migration) {
        this(memTable, memoryManager, policy, migration,
                System::currentTimeMillis, CacheConfig.defaults().maxEvictionsPerCycle());
    }

    public EvictionManager(
            MemTable memTable,
            MemoryManager memoryManager,
            EvictionPolicy policy,
            TierMigration migration,
            WALManager wal,
            TimeSource timeSource,
            int maxEvictionsPerCycle) {
        this(memTable, memoryManager, policy, migration, timeSource, maxEvictionsPerCycle, wal);
    }

    public EvictionManager(
            MemTable memTable,
            MemoryManager memoryManager,
            EvictionPolicy policy,
            TierMigration migration,
            TimeSource timeSource,
            int maxEvictionsPerCycle) {
        this(memTable, memoryManager, policy, migration, timeSource, maxEvictionsPerCycle, null);
    }

    private EvictionManager(
            MemTable memTable,
            MemoryManager memoryManager,
            EvictionPolicy policy,
            TierMigration migration,
            TimeSource timeSource,
            int maxEvictionsPerCycle,
            WALManager wal) {
        this.memTable = memTable;
        this.memoryManager = memoryManager;
        this.policy = policy;
        this.migration = migration;
        this.wal = wal;
        this.timeSource = timeSource;
        this.maxEvictionsPerCycle = maxEvictionsPerCycle;
    }

    public void onAccess(AccessEvent event) {
        policy.onAccess(event);
    }

    public EvictionPolicy policy() {
        return policy;
    }

    /** 超限时执行淘汰，直到低于配额、候选耗尽或达到本轮上限。 */
    public void maybeEvict() {
        if (!memoryManager.isOverLimit()) {
            return;
        }
        long now = timeSource.nowMillis();
        int retryBudget = MAX_MIGRATION_ATTEMPTS;
        for (int i = 0; i < maxEvictionsPerCycle && memoryManager.isOverLimit(); i++) {
            EvictionCandidate candidate = policy.selectCandidate();
            if (candidate == null) {
                return;
            }
            byte[] key = candidate.key();
            KeyValueEntry entry = memTable.getEntry(key);
            if (entry == null || !entry.isLive(now)) {
                // 过期/失效候选：清理策略状态后跳过
                policy.onAccess(new AccessEvent(key, AccessEvent.AccessOperation.DELETE, now, 0));
                continue;
            }
            MigrationResult result = migration.migrate(entry);
            switch (result) {
                case SUCCESS -> {
                    if (wal != null) {
                        try {
                            wal.append(WALEntry.delete(now, key, 0));
                        } catch (WalWriteException e) {
                            return; // 无法记录删除：保留数据，避免崩溃后复活
                        }
                    }
                    if (memTable.removePhysical(key)) {
                        policy.onAccess(new AccessEvent(key, AccessEvent.AccessOperation.EVICT, now, 0));
                    }
                }
                case FAILED -> {
                    return; // 永久失败：保留数据，终止本轮
                }
                case RETRY -> {
                    if (--retryBudget <= 0) {
                        return; // 重试预算耗尽：保留数据
                    }
                }
            }
        }
    }
}
