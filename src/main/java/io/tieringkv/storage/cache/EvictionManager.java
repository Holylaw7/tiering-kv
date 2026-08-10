package io.tieringkv.storage.cache;

import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.memory.TimeSource;

/**
 * 内存压力驱动的淘汰管理器（ADR-0012）：
 * usedMemory &gt; maxMemory 时选择候选 → 存活校验 → 迁移回调 → 删除 → EVICT 事件。
 * 每轮有上限，避免 tombstone 边界下死循环；回调/删除顺序保证"先迁移后删除"。
 */
public final class EvictionManager {

    private final MemTable memTable;
    private final MemoryManager memoryManager;
    private final EvictionPolicy policy;
    private final MigrationCallback migrationCallback;
    private final TimeSource timeSource;
    private final int maxEvictionsPerCycle;

    public EvictionManager(
            MemTable memTable,
            MemoryManager memoryManager,
            EvictionPolicy policy,
            MigrationCallback migrationCallback) {
        this(memTable, memoryManager, policy, migrationCallback,
                System::currentTimeMillis, CacheConfig.defaults().maxEvictionsPerCycle());
    }

    public EvictionManager(
            MemTable memTable,
            MemoryManager memoryManager,
            EvictionPolicy policy,
            MigrationCallback migrationCallback,
            TimeSource timeSource,
            int maxEvictionsPerCycle) {
        this.memTable = memTable;
        this.memoryManager = memoryManager;
        this.policy = policy;
        this.migrationCallback = migrationCallback;
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
            migrationCallback.migrate(entry);
            if (memTable.removePhysical(key)) {
                policy.onAccess(new AccessEvent(key, AccessEvent.AccessOperation.EVICT, now, 0));
            }
        }
    }
}
