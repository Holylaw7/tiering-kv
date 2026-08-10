# ADR-0078: MVCC Batch GC Design

## Status

Accepted

## Context

Phase 19 的 `MvccGcManager` 对每个可回收版本单独调用
`MvccStorageEngine.deleteVersion`（复制整条版本链 + 单次存储删除），
吞吐仅 19–29 MB/s，未达 100 MB/s 目标（TD-041）。瓶颈是：

- 扫描后逐 key 逐版本删除，索引列表每次整体拷贝；
- 存储层单条删除路径无法利用分段锁并行。

## Decision

新增 `io.tieringkv.mvcc.gc` 包：

- `BatchGcExecutor`：一次底层扫描 → 按 userKey 分组 → 排序 → 规划可回收
  版本（保留最新且 commitTS >= safePoint，跳过 LOCK）→ 批量删除；
- 并行 worker：按 `gc.worker.count` 分片执行，每片内按 `gc.batch.size`
  提交到 `MvccStorageEngine.deleteVersions`；
- `MvccStorageEngine.deleteVersions`：按 userKey 一次重建索引列表 +
  `StorageEngine.deleteAll`（MemTable 按分段单锁批量删除）；
- 配置：`gc.batch.size` / `gc.worker.count` / `gc.max.memory`。

禁止：

- 一次删除整个 userKey（必须保留最新可见版本）；
- 阻塞写路径（扫描使用只读 iterator，删除分批短临界区）；
- 删除 `commitTS >= safePoint` 或活跃快照仍可读的版本。

## Alternatives

1. 直接并行逐版本删除：索引拷贝成本不变，收益有限。
2. 标记-清理（mark-sweep）延迟合并：复杂度高，收益与批量路径相当。
3. 使用底层 LSM 批量 compact：依赖冷层实现，内存层仍需批量删除。

## Consequences

优点：

- 索引重建每 key 一次 O(V)，存储删除按分段批量执行；
- 并行 worker 可线性扩展吞吐。

缺点：

- 批量路径需要与 rollback/写入正确同步（索引锁 + 不可变列表语义）。

风险：

- 低；快照安全规则与 ADR-0075 保持一致。

## Implementation

- `src/main/java/io/tieringkv/mvcc/gc/`：BatchGcExecutor、GcConfig、GcResult；
- `MvccStorageEngine.deleteVersions`；
- `StorageEngine.deleteAll`（默认逐条，MemTable 覆盖为分段批量）；
- 配置：`TieringConfig.Gc` + `application.yaml`；
- 测试：MvccGcPerformanceTest / MvccGcConcurrencyTest /
  MvccGcSnapshotSafetyTest。
