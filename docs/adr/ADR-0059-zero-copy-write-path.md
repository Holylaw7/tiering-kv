# ADR-0059: Zero-Copy Write Path

## Status

Accepted

## Context

TD-033：100B 迁移仅 59.8MB/s（目标 >100MB/s）。Phase 15 写路径每条目
发生 3 次数组拷贝：`Mutation` 构造克隆、`Mutation.key()/value()` 访问器
克隆、`KeyValueEntry` 构造克隆。

## Decision

- 新增 `RawMutation(key, value, version, ttl)`：**不克隆**，所有权随
  `MemTable.applyRawBatch` 转移；调用方必须在转移后停止修改数组；
- `KeyValueEntry` 由 record 重构为 final class，新增私有 owned 构造器
  （`liveOwned`，包内可见）跳过克隆；公共构造器保持防御性克隆；
- `MemTable.applyRawBatch(List<RawMutation>)`：平面桶分组（int 计数 +
  前缀和，无装箱）、单段单锁批量插入、版本按请求顺序分配；
- `StorageEngine.applyRawBatch` 默认回退为 applyBatch（拷贝路径，
  语义等价），非 MemTable 引擎兼容；
- `StreamingMigrator` 切换为 RawMutation 路径；`MigrationScanner` 全槽位
  迁移跳过逐条目 slot 哈希；
- `SkipList.putAndGetOld`：单次查找返回被覆盖条目（热路径减半查找）；
- `MigrationStreamCursor.advance` 不再克隆 key（源快照数组稳定）。

## Alternatives

1. 修改 `Mutation` 去除防御性拷贝：破坏既有 API 的不可变契约，否决。
2. 保持 3 次拷贝：目标无法达成，否决。
3. 通过 Unsafe/内存复用绕过：工程风险高，否决。

## Consequences

优点：100B 迁移 59.8 → ~80MB/s（+34%），1KB 173 → ~240MB/s，
10KB 590 → ~700MB/s；写路径数组拷贝 3 次 → 0 次。

缺点：所有权契约要求调用方遵守（内部 API，文档化）；`KeyValueEntry`
由 record 改为 class（行为兼容，测试全绿）。

风险：目标 >100MB/s（100B）/ >300MB/s（1KB）仍未达，剩余瓶颈为每条目
固定开销（迭代器归并 + CRC + RawMutation + 分段锁），约 1.2µs/entry；
需进一步减少固定开销或并行迁移（后续阶段，如实记录）。

## Implementation

- `storage/memory/RawMutation.java`、`KeyValueEntry.java`、`MemTable.java`、
  `SkipList.java`、`StorageEngine.java`
- `migration/streaming/StreamingMigrator.java`、`MigrationScanner.java`、
  `MigrationStreamCursor.java`
- 测试：RawBatchWriteTest（20）+ 迁移零拷贝断言；基准：
  ZeroCopyMigrationBenchmarkTest。
