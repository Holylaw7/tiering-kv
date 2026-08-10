# MVCC 架构（Phase 19）

## 数据模型

- 底层键 `[userKey][writeType(1)][startTS(8)][commitTS(8)]`；
- `MvccEntry`：key/value/startTS/commitTS/writeType(PUT/DELETE/LOCK)；
- DELETE 写 DELETE 版本隐藏旧值；LOCK 为 provisional（读者不可见）；
- `MvccStorageEngine`：StorageEngine adapter + 内存版本索引
  （启动/快照恢复时 O(N) 重建）。

## Snapshot Read

`SnapshotReader.get/scan(readTS)`：只读 `commitTS <= readTS` 的
PUT/DELETE；DELETE 隐藏更早版本。

## 时间戳

`TimestampOracle`（原子单调 + 批量 + recover 不回退）+ `HybridLogicalClock`
（回拨不倒退，HLC 合并）。

## GC

`MvccGcManager`：保留每键最新版本 + `commitTS >= safePoint` 的版本；
支持手动与后台调度；指标 mvcc_versions/gc_versions/gc_bytes/safe_point。
