package io.tieringkv.storage.memory;

/**
 * 内存压力回调（ADR-0007 设计约束）。
 * Phase 2 只提供接口；Phase 3 由 LFU/ARC 实现"选择哪些键淘汰"。
 * 调用约定：必须在 MemTable 释放段锁之后触发。
 */
@FunctionalInterface
public interface EvictionCallback {

    void onMemoryPressure(long usedBytes, long maxBytes);
}
