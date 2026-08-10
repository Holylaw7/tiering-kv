# ADR-0013: Tier Migration Interface Semantics

## Status

Accepted

## Context

Phase 3 初版 `MigrationCallback` 为 `void migrate(entry)`，语义等价于"淘汰 =
删除"。进入 Phase 4/5 后，迁移将经过 WAL → Bitcask → LSM 链路，磁盘满、IO
错误、中断都可能导致迁移失败；删除必须在迁移成功后执行，否则数据永久丢失。

## Decision

将回调升级为带结果码的迁移接口：

```java
interface TierMigration {
    MigrationResult migrate(KeyValueEntry entry);
}

enum MigrationResult { SUCCESS, FAILED, RETRY }
```

1. **SUCCESS**：已安全迁移 → `MemTable.removePhysical` + EVICT 事件；
2. **FAILED**：永久失败 → 保留内存副本，终止本轮淘汰；
3. **RETRY**：瞬时失败 → 同一候选重试，本轮预算默认 3 次，耗尽后保留；
4. **顺序保证**：migrate 成功后才允许删除（先迁移、后删除）；
5. Phase 3 默认实现 `TierMigration.discard()` 返回 SUCCESS（占位语义 = 丢弃）。

## Alternatives

1. `void` 回调：无法区分失败，淘汰即删除，Phase 4/5 数据丢失风险；
2. 抛异常表达失败：用控制流表达状态，且无法表达"可重试"；
3. 异步结果回调：适合 Phase 6 迁移队列，当前同步链路用结果码更简单。

## Consequences

**优点：** 显式失败语义；删除条件明确；Phase 6 可平滑替换为真实磁盘迁移。
**缺点：** 调用方必须处理三种结果；重试预算需配置。
**风险：** RETRY 风暴 → 预算封顶 + 每轮上限（maxEvictionsPerCycle）双重约束。

## Implementation

- `io.tieringkv.storage.cache.TierMigration` + `MigrationResult`；
- `EvictionManager.maybeEvict` 按结果码分支；
- Phase 3 测试覆盖 SUCCESS / FAILED / RETRY / 重试预算 / 先迁移后删除。
