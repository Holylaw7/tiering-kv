package io.tieringkv.storage.cache;

/** 迁移结果（ADR-0013）：驱动 EvictionManager 的删除决策。 */
public enum MigrationResult {
    /** 已安全迁移，可物理移除内存副本。 */
    SUCCESS,
    /** 永久失败（如格式错误）：保留数据并终止本轮淘汰。 */
    FAILED,
    /** 瞬时失败（如磁盘繁忙）：同候选重试，预算耗尽后保留。 */
    RETRY
}
